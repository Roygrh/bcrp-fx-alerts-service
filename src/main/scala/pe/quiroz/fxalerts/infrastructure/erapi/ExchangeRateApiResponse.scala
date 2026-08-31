package pe.quiroz.fxalerts.infrastructure.erapi

import cats.syntax.all.*
import io.circe.parser.decode
import io.circe.{Decoder, HCursor}
import pe.quiroz.fxalerts.domain.alert.BcrpSeries
import pe.quiroz.fxalerts.domain.rate.{ExchangeRate, PeruvianCalendar, RateProvider}
import pe.quiroz.fxalerts.infrastructure.remote.RemoteFailure

import java.time.Instant

/**
 * Respuesta de `GET https://open.er-api.com/v6/latest/{base}` (ExchangeRate-API, acceso abierto).
 *
 * Forma real capturada (recortada):
 * {{{
 * {
 *   "result": "success",
 *   "provider": "https://www.exchangerate-api.com",
 *   "documentation": "https://www.exchangerate-api.com/docs/free",
 *   "terms_of_use": "https://www.exchangerate-api.com/terms",
 *   "time_last_update_unix": 1788134551,
 *   "time_last_update_utc": "Mon, 31 Aug 2026 00:02:31 +0000",
 *   "time_next_update_unix": 1788221261,
 *   "time_next_update_utc": "Tue, 01 Sep 2026 00:07:41 +0000",
 *   "time_eol_unix": 0,
 *   "base_code": "USD",
 *   "rates": { "USD": 1, "AED": 3.6725, ..., "PEN": 3.350827, ... }
 * }
 * }}}
 *
 * Y en caso de error de aplicación, también con HTTP 200:
 * {{{
 * { "result": "error", "error-type": "unsupported-code" }
 * }}}
 *
 * Solo `result` es obligatorio; el resto se decodifica de forma tolerante porque su ausencia se
 * trata como un caso de negocio (respuesta inutilizable), no como un fallo de decodificación.
 *
 * @param timeLastUpdateUnix
 *   instante real del dato; es la fecha que se reporta, no la de la consulta
 * @param timeEolUnix
 *   fin de vida anunciado del endpoint (`0` = sin fecha prevista)
 */
final case class ExchangeRateApiResponse(
    result: String,
    errorType: Option[String],
    timeLastUpdateUnix: Option[Long],
    timeEolUnix: Option[Long],
    baseCode: Option[String],
    rates: Map[String, BigDecimal]
):
  def isSuccess: Boolean = result == "success"

  /** Fin de vida anunciado, si el proveedor lo ha fijado. */
  def endOfLife: Option[Instant] =
    timeEolUnix.filter(_ != 0L).map(Instant.ofEpochSecond)

object ExchangeRateApiResponse:

  given Decoder[ExchangeRateApiResponse] = Decoder.instance { (cursor: HCursor) =>
    (
      cursor.get[String]("result"),
      cursor.get[Option[String]]("error-type"),
      cursor.get[Option[Long]]("time_last_update_unix"),
      cursor.get[Option[Long]]("time_eol_unix"),
      cursor.get[Option[String]]("base_code"),
      cursor.getOrElse[Map[String, BigDecimal]]("rates")(Map.empty)
    ).mapN(ExchangeRateApiResponse.apply)
  }

  def parse(body: String): Either[RemoteFailure, ExchangeRateApiResponse] =
    decode[ExchangeRateApiResponse](body).leftMap(error =>
      RemoteFailure.UnexpectedPayload(error.getMessage, body.length)
    )

  /**
   * Convierte la respuesta en el tipo de cambio de la serie solicitada.
   *
   *   - `result` distinto de `success` es un error de aplicación del proveedor
   *     ([[RemoteFailure.ProviderError]]), aunque el HTTP haya sido 200.
   *   - La ausencia de la moneda esperada en `rates`, de `time_last_update_unix` o una base
   *     distinta de USD dejan la respuesta inutilizable ([[RemoteFailure.UnexpectedPayload]]).
   *
   * La fecha del dato se obtiene de `time_last_update_unix` expresado en el calendario de
   * referencia del servicio ([[PeruvianCalendar]]): el proveedor actualiza poco después de las
   * 00:00 UTC, que en Lima todavía es el día anterior.
   */
  def toExchangeRate(
      response: ExchangeRateApiResponse,
      series: BcrpSeries,
      currency: String,
      bodyLength: Int
  ): Either[RemoteFailure, ExchangeRate] =
    def unusable(reason: String) = RemoteFailure.UnexpectedPayload(reason, bodyLength)
    for
      _ <- Either.cond(
        response.isSuccess,
        (),
        RemoteFailure.ProviderError(response.errorType.getOrElse("sin detalle"))
      )
      _ <- Either.cond(
        response.baseCode.forall(_ == "USD"),
        (),
        unusable(s"base_code inesperado: ${response.baseCode.getOrElse("ausente")}")
      )
      updatedAt <- response.timeLastUpdateUnix.toRight(unusable("falta time_last_update_unix"))
      value     <- response.rates.get(currency).toRight(unusable(s"rates no incluye $currency"))
      _         <- Either.cond(value > 0, (), unusable(s"valor no positivo para $currency: $value"))
    yield ExchangeRate(
      series = series,
      date = Instant.ofEpochSecond(updatedAt).atZone(PeruvianCalendar.zone).toLocalDate,
      value = value,
      provider = RateProvider.ExchangeRateApi
    )
