package pe.quiroz.fxalerts.infrastructure.http.rate

import cats.effect.IO
import io.circe.Json
import io.circe.parser.parse
import munit.CatsEffectSuite
import org.http4s.implicits.*
import org.http4s.{HttpApp, Method, Request, Response, Status}
import org.typelevel.ci.*
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
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

/** Rutas del tipo de cambio sin red: el servicio se monta sobre un doble del puerto. */
class RateRoutesSuite extends CatsEffectSuite:

  private given Logger[IO] = Slf4jLogger.getLogger[IO]

  private val currentUri = uri"/api/v1/rates/current"

  private def withApp[A](
      behaviour: IO[Either[ExchangeRateError, RateSnapshot]]
  )(body: HttpApp[IO] => IO[A]): IO[A] =
    StubExchangeRateSource(behaviour).flatMap { source =>
      val routes = RateRoutes[IO](ExchangeRateService[IO](source))
      body(HttpApi.fromEndpoints[IO](routes.serverEndpoints))
    }

  private def bodyJson(response: Response[IO]): IO[Json] =
    response.bodyText.compile.string.map(raw => parse(raw).fold(throw _, identity))

  private def stringAt(json: Json, field: String): Option[String] =
    json.hcursor.get[String](field).toOption

  private def sourceField[A: io.circe.Decoder](json: Json, field: String): Option[A] =
    json.hcursor.downField("source").get[A](field).toOption

  private def assertProblem(response: Response[IO], status: Status): Unit =
    assertEquals(response.status, status)
    val contentType = response.headers.get(ci"Content-Type").map(_.head.value)
    assert(
      contentType.exists(_.startsWith("application/problem+json")),
      s"Content-Type inesperado: $contentType"
    )

  test("GET /api/v1/rates/current responde 200 con valor, fecha, serie, frescura y fuente oficial"):
    val retrievedAt = Instant.now().minusSeconds(90)
    withApp(StubExchangeRateSource.fixed(StubExchangeRateSource.snapshotAt(retrievedAt))) { app =>
      for
        response <- app.run(Request[IO](Method.GET, currentUri))
        json     <- bodyJson(response)
      yield
        assertEquals(response.status, Status.Ok)
        assertEquals(stringAt(json, "series"), Some("PD04640PD"))
        assertEquals(json.hcursor.get[BigDecimal]("value").toOption, Some(BigDecimal("3.523")))
        assertEquals(stringAt(json, "date"), Some("2026-08-28"))
        assertEquals(stringAt(json, "retrievedAt"), Some(retrievedAt.toString))
        assertEquals(stringAt(json, "freshness"), Some("FRESH"))
        assert(json.hcursor.get[Long]("ageSeconds").exists(_ >= 90L))
        assertEquals(sourceField[String](json, "id"), Some("BCRP"))
        assertEquals(sourceField[Boolean](json, "official"), Some(true))
        assert(sourceField[String](json, "name").exists(_.contains("SBS")))
        assert(sourceField[String](json, "measures").exists(_.contains("venta")))
        assertEquals(json.hcursor.downField("source").downField("attribution").succeeded, false)
    }

  test("un dato del respaldo se expone como no oficial, con qué mide y la atribución exigida"):
    val snapshot = StubExchangeRateSource.snapshotAt(
      Instant.now(),
      provider = RateProvider.ExchangeRateApi
    )
    withApp(StubExchangeRateSource.fixed(snapshot)) { app =>
      for
        response <- app.run(Request[IO](Method.GET, currentUri))
        json     <- bodyJson(response)
      yield
        assertEquals(response.status, Status.Ok)
        assertEquals(json.hcursor.get[BigDecimal]("value").toOption, Some(BigDecimal("3.350827")))
        assertEquals(stringAt(json, "date"), Some("2026-08-30"))
        assertEquals(stringAt(json, "freshness"), Some("FRESH"))
        assertEquals(sourceField[String](json, "id"), Some("ERAPI"))
        assertEquals(sourceField[Boolean](json, "official"), Some(false))
        assertEquals(sourceField[String](json, "name"), Some("ExchangeRate-API"))
        assert(sourceField[String](json, "measures").exists(_.contains("no oficial")))
        assert(
          sourceField[String](json, "attribution").exists(_.contains("Rates By Exchange Rate API"))
        )
    }

  test("un dato servido desde caché con las fuentes caídas se marca STALE"):
    val snapshot = StubExchangeRateSource.snapshotAt(Instant.now(), Freshness.Stale)
    withApp(StubExchangeRateSource.fixed(snapshot)) { app =>
      for
        response <- app.run(Request[IO](Method.GET, currentUri))
        json     <- bodyJson(response)
      yield
        assertEquals(response.status, Status.Ok)
        assertEquals(stringAt(json, "freshness"), Some("STALE"))
    }

  test("sin dato publicado en la ventana responde 404 Problem Details"):
    withApp(StubExchangeRateSource.failing(ExchangeRateNotPublished(BcrpSeries.UsdPenSbsSell))) {
      app =>
        for
          response <- app.run(Request[IO](Method.GET, currentUri))
          json     <- bodyJson(response)
        yield
          assertProblem(response, Status.NotFound)
          assertEquals(stringAt(json, "type"), Some("urn:fx-alerts:problem:not-found"))
          assertEquals(json.hcursor.get[Int]("status").toOption, Some(404))
          assert(stringAt(json, "detail").exists(_.contains("PD04640PD")))
    }

  test("con todas las fuentes caídas y sin caché responde 503 Problem Details"):
    withApp(StubExchangeRateSource.failing(ExchangeRateUnavailable(BcrpSeries.UsdPenSbsSell))) {
      app =>
        for
          response <- app.run(Request[IO](Method.GET, currentUri))
          json     <- bodyJson(response)
        yield
          assertProblem(response, Status.ServiceUnavailable)
          assertEquals(stringAt(json, "type"), Some("urn:fx-alerts:problem:source-unavailable"))
          assertEquals(stringAt(json, "title"), Some("Fuente de datos no disponible"))
          assertEquals(json.hcursor.get[Int]("status").toOption, Some(503))
          assert(stringAt(json, "detail").exists(_.nonEmpty))
    }

  test("el documento OpenAPI incluye el endpoint de tipo de cambio y la procedencia"):
    withApp(StubExchangeRateSource.freshSample) { app =>
      for
        response <- app.run(Request[IO](Method.GET, uri"/docs/docs.yaml"))
        document <- response.bodyText.compile.string
      yield
        assertEquals(response.status, Status.Ok)
        assert(document.contains("/api/v1/rates/current"))
        assert(document.contains("STALE"))
        assert(document.contains("official"))
        assert(document.contains("Rates By Exchange Rate API"))
    }
