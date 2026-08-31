package pe.quiroz.fxalerts.infrastructure.http.health

import cats.effect.IO
import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import munit.CatsEffectSuite
import org.http4s.implicits.*
import org.http4s.{HttpApp, Method, Request, Status}
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import pe.quiroz.fxalerts.application.health.{DatabaseHealthCheck, HealthService}
import pe.quiroz.fxalerts.application.rate.{
  ExchangeRateService,
  Freshness,
  RateSnapshot,
  StubExchangeRateSource
}
import pe.quiroz.fxalerts.domain.DomainError.{
  ExchangeRateError,
  ExchangeRateNotPublished,
  ExchangeRateUnavailable
}
import pe.quiroz.fxalerts.domain.alert.BcrpSeries
import pe.quiroz.fxalerts.domain.rate.RateProvider
import pe.quiroz.fxalerts.infrastructure.http.HttpApi

import java.time.Instant
import scala.concurrent.duration.*

/**
 * `/health` sin infraestructura: base de datos y fuentes del tipo de cambio se sustituyen por
 * dobles para cubrir cada combinación de estados y el criterio degradado/caído.
 */
class HealthRoutesSuite extends CatsEffectSuite:

  private given Logger[IO] = Slf4jLogger.getLogger[IO]

  private val databaseUp: IO[Unit]   = IO.unit
  private val databaseDown: IO[Unit] = IO.raiseError(new RuntimeException("sin conexión"))

  private val freshOfficial: IO[Either[ExchangeRateError, RateSnapshot]] =
    StubExchangeRateSource.fixed(StubExchangeRateSource.snapshotAt(Instant.now()))

  private val freshFallback: IO[Either[ExchangeRateError, RateSnapshot]] =
    StubExchangeRateSource.fixed(
      StubExchangeRateSource.snapshotAt(Instant.now(), provider = RateProvider.ExchangeRateApi)
    )

  private val stale: IO[Either[ExchangeRateError, RateSnapshot]] =
    StubExchangeRateSource.fixed(StubExchangeRateSource.snapshotAt(Instant.now(), Freshness.Stale))

  private def httpApp(
      databasePing: IO[Unit],
      rateBehaviour: IO[Either[ExchangeRateError, RateSnapshot]]
  ): IO[HttpApp[IO]] =
    StubExchangeRateSource(rateBehaviour).map { source =>
      val databaseCheck = new DatabaseHealthCheck[IO]:
        def ping: IO[Unit] = databasePing
      val service = HealthService[IO](
        databaseCheck,
        1.second,
        ExchangeRateService[IO](source),
        200.millis
      )
      HttpApi.fromEndpoints[IO](HealthRoutes[IO](service).serverEndpoints)
    }

  private def check(
      databasePing: IO[Unit],
      rateBehaviour: IO[Either[ExchangeRateError, RateSnapshot]]
  ): IO[(Status, Json)] =
    for
      app      <- httpApp(databasePing, rateBehaviour)
      response <- app.run(Request[IO](Method.GET, uri"/health"))
      body     <- response.bodyText.compile.string
    yield (response.status, parse(body).fold(throw _, identity))

  private def overall(json: Json): Option[String] = json.hcursor.get[String]("status").toOption

  private def componentStatus(json: Json, component: String): Option[String] =
    json.hcursor.downField(component).get[String]("status").toOption

  private def componentDetail(json: Json, component: String): Option[String] =
    json.hcursor.downField(component).get[String]("detail").toOption

  private def rateSource(json: Json): Option[String] =
    json.hcursor.downField("rates").get[String]("source").toOption

  private def rateOfficial(json: Json): Option[Boolean] =
    json.hcursor.downField("rates").get[Boolean]("official").toOption

  test("200 UP cuando la base de datos responde y el tipo de cambio viene de la fuente oficial"):
    check(databaseUp, freshOfficial).map { (status, json) =>
      assertEquals(status, Status.Ok)
      assertEquals(
        json,
        Json.obj(
          "status"   -> "UP".asJson,
          "database" -> Json.obj("status" -> "UP".asJson),
          "rates"    -> Json.obj(
            "status"   -> "UP".asJson,
            "source"   -> "BCRP".asJson,
            "official" -> true.asJson
          )
        )
      )
    }

  test("200 DEGRADED cuando el tipo de cambio se sirve desde el respaldo no oficial"):
    check(databaseUp, freshFallback).map { (status, json) =>
      assertEquals(status, Status.Ok)
      assertEquals(overall(json), Some("DEGRADED"))
      assertEquals(componentStatus(json, "database"), Some("UP"))
      assertEquals(componentStatus(json, "rates"), Some("DEGRADED"))
      assertEquals(rateSource(json), Some("ERAPI"))
      assertEquals(rateOfficial(json), Some(false))
      assert(componentDetail(json, "rates").exists(_.contains("no oficial")))
    }

  test("200 DEGRADED cuando las fuentes no responden pero se sirve desde caché"):
    check(databaseUp, stale).map { (status, json) =>
      assertEquals(status, Status.Ok)
      assertEquals(overall(json), Some("DEGRADED"))
      assertEquals(componentStatus(json, "rates"), Some("DEGRADED"))
      assertEquals(rateSource(json), Some("BCRP"))
      assert(componentDetail(json, "rates").exists(_.contains("último dato conocido")))
    }

  test("200 DEGRADED (rates DOWN) cuando ninguna fuente responde y no hay nada en caché"):
    val unavailable =
      StubExchangeRateSource.failing(ExchangeRateUnavailable(BcrpSeries.UsdPenSbsSell))
    check(databaseUp, unavailable).map { (status, json) =>
      assertEquals(status, Status.Ok)
      assertEquals(overall(json), Some("DEGRADED"))
      assertEquals(componentStatus(json, "rates"), Some("DOWN"))
      assertEquals(rateSource(json), None)
      assert(componentDetail(json, "rates").exists(_.contains("no hay dato en caché")))
    }

  test("200 UP cuando la fuente responde pero no hay dato publicado en la ventana"):
    val notPublished =
      StubExchangeRateSource.failing(ExchangeRateNotPublished(BcrpSeries.UsdPenSbsSell))
    check(databaseUp, notPublished).map { (status, json) =>
      assertEquals(status, Status.Ok)
      assertEquals(overall(json), Some("UP"))
      assertEquals(componentStatus(json, "rates"), Some("UP"))
      assert(componentDetail(json, "rates").exists(_.contains("no hay dato publicado")))
    }

  test("200 DEGRADED cuando la verificación del tipo de cambio no concluye a tiempo"):
    check(databaseUp, IO.never).map { (status, json) =>
      assertEquals(status, Status.Ok)
      assertEquals(overall(json), Some("DEGRADED"))
      assertEquals(componentStatus(json, "rates"), Some("DEGRADED"))
      assert(componentDetail(json, "rates").exists(_.contains("tiempo límite")))
    }

  test("503 DOWN cuando la base de datos no responde, aunque el tipo de cambio esté operativo"):
    check(databaseDown, freshOfficial).map { (status, json) =>
      assertEquals(status, Status.ServiceUnavailable)
      assertEquals(overall(json), Some("DOWN"))
      assertEquals(componentStatus(json, "database"), Some("DOWN"))
      assertEquals(componentStatus(json, "rates"), Some("UP"))
    }
