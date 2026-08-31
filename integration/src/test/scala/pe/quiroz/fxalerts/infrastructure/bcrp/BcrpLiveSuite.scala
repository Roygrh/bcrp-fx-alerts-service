package pe.quiroz.fxalerts.infrastructure.bcrp

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.Uri
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import pe.quiroz.fxalerts.domain.DomainError.{ExchangeRateNotPublished, ExchangeRateUnavailable}
import pe.quiroz.fxalerts.domain.alert.BcrpSeries
import pe.quiroz.fxalerts.infrastructure.config.{BcrpConfig, RemoteCallConfig}
import pe.quiroz.fxalerts.infrastructure.remote.RemoteHttpClient

import scala.concurrent.duration.*

/**
 * Prueba contra la API real del BCRP.
 *
 * Depende de la red y de un tercero sin SLA, así que está doblemente apartada de la suite normal:
 * vive en el subproyecto `integration` (que `sbt test` no ejecuta) y, además, se omite salvo que la
 * variable de entorno `BCRP_LIVE_TESTS` valga `true`:
 *
 * {{{
 * BCRP_LIVE_TESTS=true sbt "integration/testOnly *BcrpLiveSuite"
 * }}}
 *
 * `BCRP_BASE_URL` permite apuntar a otra instancia (por ejemplo, un simulador local).
 */
class BcrpLiveSuite extends CatsEffectSuite:

  private given Logger[IO] = Slf4jLogger.getLogger[IO]

  private val enabled = sys.env.get("BCRP_LIVE_TESTS").exists(_.equalsIgnoreCase("true"))

  private val config = BcrpConfig(
    baseUri = sys.env
      .get("BCRP_BASE_URL")
      .flatMap(Uri.fromString(_).toOption)
      .getOrElse(Uri.unsafeFromString("https://estadisticas.bcrp.gob.pe/estadisticas/series/api")),
    lookbackDays = 7,
    call = RemoteCallConfig(
      connectTimeout = 5.seconds,
      readTimeout = 10.seconds,
      maxRetries = 1,
      retryBackoff = 1.second
    )
  )

  override def munitIOTimeout: Duration = 2.minutes

  test("la API real devuelve un tipo de cambio publicado en los últimos días"):
    assume(enabled, "Prueba en vivo desactivada; exporta BCRP_LIVE_TESTS=true para ejecutarla")
    RemoteHttpClient.resource[IO](config.call).use { client =>
      BcrpExchangeRateClient[IO](client, config).latest(BcrpSeries.UsdPenSbsSell).flatMap {
        case Right(snapshot) =>
          IO.println(
            s"BCRP en vivo: ${snapshot.rate.series.code} ${snapshot.rate.date} = " +
              s"${snapshot.rate.value} (${snapshot.freshness})"
          ) *> IO(assert(snapshot.rate.value > 0))
        case Left(ExchangeRateNotPublished(series)) =>
          IO(fail(s"La API respondió pero sin dato publicado en la ventana para ${series.code}"))
        case Left(ExchangeRateUnavailable(series)) =>
          IO(
            fail(
              s"La API no pudo consultarse para ${series.code}; revisa el log: si el cuerpo no es " +
                "JSON, el proxy de seguridad del BCRP está devolviendo su página de desafío"
            )
          )
      }
    }
