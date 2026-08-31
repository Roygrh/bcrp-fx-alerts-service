package pe.quiroz.fxalerts.infrastructure.erapi

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.Uri
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import pe.quiroz.fxalerts.domain.alert.BcrpSeries
import pe.quiroz.fxalerts.domain.rate.RateProvider
import pe.quiroz.fxalerts.infrastructure.config.{ExchangeRateApiConfig, RemoteCallConfig}
import pe.quiroz.fxalerts.infrastructure.remote.RemoteHttpClient

import scala.concurrent.duration.*

/**
 * Prueba contra la API real de ExchangeRate-API (fuente de respaldo).
 *
 * Igual que [[pe.quiroz.fxalerts.infrastructure.bcrp.BcrpLiveSuite]], depende de la red y de un
 * tercero, así que vive en `integration` y se omite salvo que `BCRP_LIVE_TESTS=true`.
 */
class ExchangeRateApiLiveSuite extends CatsEffectSuite:

  private given Logger[IO] = Slf4jLogger.getLogger[IO]

  private val enabled = sys.env.get("BCRP_LIVE_TESTS").exists(_.equalsIgnoreCase("true"))

  private val config = ExchangeRateApiConfig(
    baseUri = sys.env
      .get("ERAPI_BASE_URL")
      .flatMap(Uri.fromString(_).toOption)
      .getOrElse(Uri.unsafeFromString("https://open.er-api.com/v6")),
    call = RemoteCallConfig(
      connectTimeout = 5.seconds,
      readTimeout = 10.seconds,
      maxRetries = 1,
      retryBackoff = 1.second
    )
  )

  override def munitIOTimeout: Duration = 2.minutes

  test("la API real devuelve una tasa USD/PEN marcada como no oficial"):
    assume(enabled, "Prueba en vivo desactivada; exporta BCRP_LIVE_TESTS=true para ejecutarla")
    RemoteHttpClient.resource[IO](config.call).use { client =>
      for
        source <- ExchangeRateApiClient[IO](client, config)
        result <- source.latest(BcrpSeries.UsdPenSbsSell)
      yield
        val snapshot = result.fold(e => fail(s"Se esperaba un dato: $e"), identity)
        assertEquals(snapshot.rate.provider, RateProvider.ExchangeRateApi)
        assert(!snapshot.rate.official)
        assert(snapshot.rate.value > 0)
    }
