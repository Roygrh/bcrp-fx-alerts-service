package pe.quiroz.fxalerts.infrastructure.erapi

import munit.FunSuite
import pe.quiroz.fxalerts.domain.alert.BcrpSeries
import pe.quiroz.fxalerts.domain.rate.RateProvider
import pe.quiroz.fxalerts.infrastructure.remote.RemoteFailure

import java.time.{Instant, LocalDate}
import scala.io.Source

/**
 * Parseo de respuestas reales de ExchangeRate-API (capturadas el 2026-08-31 desde
 * `https://open.er-api.com/v6/latest/USD` y `/latest/XXX`), sin red.
 */
class ExchangeRateApiResponseSuite extends FunSuite:

  private val series = BcrpSeries.UsdPenSbsSell

  private def fixture(name: String): String =
    val stream = Option(getClass.getResourceAsStream(s"/erapi/$name"))
      .getOrElse(fail(s"No se encuentra el recurso de prueba erapi/$name"))
    try Source.fromInputStream(stream, "UTF-8").mkString
    finally stream.close()

  private val success = fixture("latest-USD.json")
  private val error   = fixture("latest-XXX-error.json")

  private def convert(body: String) =
    ExchangeRateApiResponse
      .parse(body)
      .flatMap(ExchangeRateApiResponse.toExchangeRate(_, series, "PEN", body.length))

  test("decodifica la respuesta real: result, metadatos temporales, base y tabla de tasas"):
    val response = ExchangeRateApiResponse.parse(success).fold(f => fail(f.describe), identity)
    assert(response.isSuccess)
    assertEquals(response.baseCode, Some("USD"))
    assertEquals(response.timeLastUpdateUnix, Some(1788134551L))
    assertEquals(response.timeEolUnix, Some(0L))
    assertEquals(response.endOfLife, None)
    assertEquals(response.rates.get("PEN"), Some(BigDecimal("3.350827")))
    assertEquals(response.rates.get("USD"), Some(BigDecimal(1)))
    assert(response.rates.size > 100, s"tasas: ${response.rates.size}")

  test("produce un ExchangeRate no oficial con la fecha del dato en el calendario peruano"):
    val rate = convert(success).fold(f => fail(f.describe), identity)
    assertEquals(rate.series, series)
    assertEquals(rate.value, BigDecimal("3.350827"))
    assertEquals(rate.provider, RateProvider.ExchangeRateApi)
    assert(!rate.official)
    // 1788134551 = 2026-08-31T00:02:31Z, que en Lima (UTC-5) es todavía el 30 de agosto.
    assertEquals(Instant.ofEpochSecond(1788134551L).toString, "2026-08-31T00:02:31Z")
    assertEquals(rate.date, LocalDate.of(2026, 8, 30))

  test("\"result\": \"error\" (HTTP 200) es un error del proveedor, no un fallo de decodificación"):
    val response = ExchangeRateApiResponse.parse(error).fold(f => fail(f.describe), identity)
    assert(!response.isSuccess)
    assertEquals(response.errorType, Some("unsupported-code"))
    assertEquals(convert(error), Left(RemoteFailure.ProviderError("unsupported-code")))

  test("la ausencia de PEN en rates deja la respuesta inutilizable"):
    val withoutPen = success.replace("\"PEN\":3.350827,", "")
    assert(!withoutPen.contains("\"PEN\""), "el fixture debía quedar sin PEN")
    convert(withoutPen) match
      case Left(RemoteFailure.UnexpectedPayload(reason, _)) =>
        assert(reason.contains("PEN"), reason)
      case other => fail(s"Se esperaba UnexpectedPayload: $other")

  test("la ausencia de time_last_update_unix deja la respuesta inutilizable"):
    val withoutTime = success.replace("\"time_last_update_unix\":1788134551,", "")
    convert(withoutTime) match
      case Left(RemoteFailure.UnexpectedPayload(reason, _)) =>
        assert(reason.contains("time_last_update_unix"), reason)
      case other => fail(s"Se esperaba UnexpectedPayload: $other")

  test("una base distinta de USD deja la respuesta inutilizable"):
    val otherBase = success.replace("\"base_code\":\"USD\"", "\"base_code\":\"EUR\"")
    assert(convert(otherBase).left.exists(_.isInstanceOf[RemoteFailure.UnexpectedPayload]))

  test("time_eol_unix distinto de cero se expone como fin de vida anunciado"):
    val withEol  = success.replace("\"time_eol_unix\":0", "\"time_eol_unix\":1800000000")
    val response = ExchangeRateApiResponse.parse(withEol).fold(f => fail(f.describe), identity)
    assertEquals(response.endOfLife, Some(Instant.ofEpochSecond(1800000000L)))

  test("un cuerpo que no es JSON se rechaza con motivo"):
    assert(ExchangeRateApiResponse.parse("<html></html>").isLeft)
    assert(ExchangeRateApiResponse.parse("""{"provider":"x"}""").isLeft)
