package pe.quiroz.fxalerts.infrastructure.erapi

import cats.effect.{Ref, Temporal}
import cats.syntax.all.*
import org.http4s.client.Client
import org.typelevel.log4cats.Logger
import pe.quiroz.fxalerts.application.rate.{ExchangeRateSource, Freshness, RateSnapshot}
import pe.quiroz.fxalerts.domain.DomainError.{ExchangeRateError, ExchangeRateUnavailable}
import pe.quiroz.fxalerts.domain.alert.BcrpSeries
import pe.quiroz.fxalerts.domain.rate.ExchangeRate
import pe.quiroz.fxalerts.infrastructure.config.ExchangeRateApiConfig
import pe.quiroz.fxalerts.infrastructure.remote.{RemoteCall, RemoteFailure}

import java.time.Instant

/**
 * Adaptador de respaldo de [[ExchangeRateSource]] sobre ExchangeRate-API (open.er-api.com).
 *
 * Es una fuente **no oficial**: entrega una tasa de mercado agregada, no el tipo de cambio venta
 * del sistema bancario. Cada valor sale marcado con
 * [[pe.quiroz.fxalerts.domain.rate.RateProvider.ExchangeRateApi]] para que ningún consumidor pueda
 * confundirlo con el dato oficial. El proveedor exige atribución ("Rates By Exchange Rate API"),
 * que se incluye en la respuesta HTTP y en el README.
 *
 * Particularidades de la API:
 *   - `result` indica éxito o error de aplicación aunque el HTTP sea 200; se trata explícitamente.
 *   - La actualización es diaria; la fecha reportada es la de `time_last_update_unix`.
 *   - `time_eol_unix` anuncia el fin de vida del endpoint; si trae valor se registra con nivel de
 *     aviso la primera vez que se observa.
 *   - Nunca devuelve "sin dato publicado": una respuesta correcta siempre trae valor, por lo que
 *     este adaptador solo produce `Right` o `ExchangeRateUnavailable`.
 *
 * Tiempos de espera, reintentos y registro los aporta [[RemoteCall]], igual que en el cliente del
 * BCRP.
 */
final class ExchangeRateApiClient[F[_]: Temporal: Logger] private (
    client: Client[F],
    config: ExchangeRateApiConfig,
    endOfLifeWarned: Ref[F, Boolean]
) extends ExchangeRateSource[F]:

  private val call = RemoteCall[F](client, config.call)

  def latest(series: BcrpSeries): F[Either[ExchangeRateError, RateSnapshot]] =
    val currency = ExchangeRateApiClient.currencyFor(series)
    val uri      = config.baseUri / "latest" / "USD"
    for
      outcome <- call.get(s"ExchangeRate-API USD/$currency", uri)(
        interpret(series, currency, _)
      ) { case (rate, _) => s"dato ${rate.date} = ${rate.value}" }
      _           <- outcome.traverse_ { case (_, endOfLife) => warnEndOfLife(endOfLife) }
      retrievedAt <- Temporal[F].realTimeInstant
    yield outcome match
      case Right((rate, _)) => Right(RateSnapshot(rate, retrievedAt, Freshness.Fresh))
      case Left(_)          => Left(ExchangeRateUnavailable(series))

  private def interpret(
      series: BcrpSeries,
      currency: String,
      body: String
  ): Either[RemoteFailure, (ExchangeRate, Option[Instant])] =
    ExchangeRateApiResponse.parse(body).flatMap { response =>
      ExchangeRateApiResponse
        .toExchangeRate(response, series, currency, body.length)
        .map(rate => (rate, response.endOfLife))
    }

  /**
   * Aviso operativo, una sola vez por proceso, si el proveedor ha anunciado el fin del endpoint.
   */
  private def warnEndOfLife(endOfLife: Option[Instant]): F[Unit] =
    endOfLife.traverse_ { instant =>
      endOfLifeWarned.getAndSet(true).flatMap { alreadyWarned =>
        Logger[F]
          .warn(
            s"ExchangeRate-API anuncia el fin de vida del endpoint de acceso abierto para $instant; " +
              "hay que planificar la sustitución de la fuente de respaldo"
          )
          .unlessA(alreadyWarned)
      }
    }

object ExchangeRateApiClient:

  def apply[F[_]: Temporal: Logger](
      client: Client[F],
      config: ExchangeRateApiConfig
  ): F[ExchangeRateApiClient[F]] =
    Ref.of[F, Boolean](false).map(new ExchangeRateApiClient[F](client, config, _))

  /**
   * Moneda de `rates` (con base USD) que aproxima cada serie del catálogo. El `match` es exhaustivo
   * a propósito: una serie nueva obliga a decidir aquí si el respaldo puede aproximarla.
   */
  def currencyFor(series: BcrpSeries): String =
    series match
      case BcrpSeries.UsdPenSbsSell => "PEN"
