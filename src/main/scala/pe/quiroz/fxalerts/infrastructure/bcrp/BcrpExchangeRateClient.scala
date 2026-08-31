package pe.quiroz.fxalerts.infrastructure.bcrp

import cats.effect.Temporal
import cats.syntax.all.*
import org.http4s.Uri
import org.http4s.client.Client
import org.typelevel.log4cats.Logger
import pe.quiroz.fxalerts.application.rate.{ExchangeRateSource, Freshness, RateSnapshot}
import pe.quiroz.fxalerts.domain.DomainError.{
  ExchangeRateError,
  ExchangeRateNotPublished,
  ExchangeRateUnavailable
}
import pe.quiroz.fxalerts.domain.alert.BcrpSeries
import pe.quiroz.fxalerts.domain.rate.{ExchangeRate, PeruvianCalendar}
import pe.quiroz.fxalerts.infrastructure.config.BcrpConfig
import pe.quiroz.fxalerts.infrastructure.remote.{RemoteCall, RemoteFailure}

import java.time.LocalDate

/**
 * Adaptador de [[ExchangeRateSource]] sobre la API pública de series del BCRP (fuente oficial).
 *
 * Por cada consulta pide la serie en formato JSON para la ventana `[hoy - lookbackDays, hoy]` (hoy
 * según el calendario de Lima, que es el de la fuente) y se queda con el último periodo que trae
 * valor. La ventana acotada evita descargar el histórico completo y basta para atravesar un fin de
 * semana largo.
 *
 * Tiempos de espera, reintentos y registro los aporta [[RemoteCall]], la política común a todos los
 * adaptadores HTTP. Una respuesta 2xx cuyo cuerpo no es el JSON esperado (por ejemplo, la página de
 * desafío del proxy de seguridad del BCRP) se clasifica como fallo no transitorio.
 */
final class BcrpExchangeRateClient[F[_]: Temporal: Logger](client: Client[F], config: BcrpConfig)
    extends ExchangeRateSource[F]:

  private val call = RemoteCall[F](client, config.call)

  def latest(series: BcrpSeries): F[Either[ExchangeRateError, RateSnapshot]] =
    for
      today <- Temporal[F].realTimeInstant.map(_.atZone(PeruvianCalendar.zone).toLocalDate)
      uri = requestUri(series, today.minusDays(config.lookbackDays.toLong), today)
      outcome     <- call.get(s"BCRP ${series.code}", uri)(interpret(series, _))(describe)
      retrievedAt <- Temporal[F].realTimeInstant
    yield outcome match
      case Right(Some(rate)) => Right(RateSnapshot(rate, retrievedAt, Freshness.Fresh))
      case Right(None)       => Left(ExchangeRateNotPublished(series))
      case Left(_)           => Left(ExchangeRateUnavailable(series))

  /** `{base}/{código}/json/{aaaa-mm-dd}/{aaaa-mm-dd}/esp`. */
  private def requestUri(series: BcrpSeries, from: LocalDate, to: LocalDate): Uri =
    config.baseUri / series.code / "json" / from.toString / to.toString / "esp"

  private def interpret(
      series: BcrpSeries,
      body: String
  ): Either[RemoteFailure, Option[ExchangeRate]] =
    BcrpResponse
      .parse(body)
      .flatMap(BcrpResponse.latestPublished(_, series))
      .leftMap(reason => RemoteFailure.UnexpectedPayload(reason, body.length))

  private def describe(rate: Option[ExchangeRate]): String =
    rate.fold("sin dato publicado en la ventana")(r => s"último dato ${r.date} = ${r.value}")
