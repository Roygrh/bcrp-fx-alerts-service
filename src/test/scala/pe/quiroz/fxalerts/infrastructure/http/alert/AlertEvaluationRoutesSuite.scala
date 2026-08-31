package pe.quiroz.fxalerts.infrastructure.http.alert

import cats.effect.IO
import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import munit.CatsEffectSuite
import org.http4s.headers.`Content-Type`
import org.http4s.implicits.*
import org.http4s.{HttpApp, MediaType, Method, Request, Response, Status, Uri}
import org.typelevel.ci.*
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import pe.quiroz.fxalerts.application.alert.{
  AlertEvaluationService,
  AlertService,
  InMemoryAlertRepository
}
import pe.quiroz.fxalerts.application.rate.{
  ExchangeRateService,
  Freshness,
  RateSnapshot,
  StubExchangeRateSource
}
import pe.quiroz.fxalerts.application.security.Scope
import pe.quiroz.fxalerts.domain.DomainError.{
  ExchangeRateError,
  ExchangeRateNotPublished,
  ExchangeRateUnavailable
}
import pe.quiroz.fxalerts.domain.alert.BcrpSeries
import pe.quiroz.fxalerts.domain.rate.RateProvider
import pe.quiroz.fxalerts.infrastructure.http.HttpApi
import pe.quiroz.fxalerts.infrastructure.http.auth.TestTokens
import pe.quiroz.fxalerts.infrastructure.http.auth.TestTokens.*
import pe.quiroz.fxalerts.infrastructure.http.rate.RateRoutes

import java.time.Instant

/**
 * `GET /api/v1/alerts/evaluation` sin red ni base de datos: repositorio en memoria y fuente de tipo
 * de cambio guionizada. Se montan también las rutas de tipo de cambio para comprobar que la
 * evaluación reutiliza exactamente su representación y sus errores. El dato oficial de muestra vale
 * 3.523 y el del respaldo 3.350827.
 */
class AlertEvaluationRoutesSuite extends CatsEffectSuite:

  private given Logger[IO] = Slf4jLogger.getLogger[IO]

  private val alertsUri     = uri"/api/v1/alerts"
  private val evaluationUri = uri"/api/v1/alerts/evaluation"
  private val currentUri    = uri"/api/v1/rates/current"

  private val owner = "cliente-001"
  private val other = "cliente-002"

  private def withApp[A](
      behaviour: IO[Either[ExchangeRateError, RateSnapshot]]
  )(body: HttpApp[IO] => IO[A]): IO[A] =
    for
      repository <- InMemoryAlertRepository.empty
      source     <- StubExchangeRateSource(behaviour)
      rateService = ExchangeRateService[IO](source)
      alertRoutes = AlertRoutes[IO](
        AlertService[IO](repository),
        AlertEvaluationService[IO](repository, rateService),
        TestTokens.auth
      )
      rateRoutes = RateRoutes[IO](rateService, TestTokens.auth)
      result <- body(
        HttpApi.fromEndpoints[IO](alertRoutes.serverEndpoints ++ rateRoutes.serverEndpoints)
      )
    yield result

  private def evaluation(clientId: String = owner, scopes: Scope*): Request[IO] =
    Request[IO](Method.GET, evaluationUri).authenticatedAs(clientId, scopes*)

  private def jsonRequest(method: Method, uri: Uri, body: Json): Request[IO] =
    Request[IO](method, uri)
      .withEntity(body.noSpaces)
      .withContentType(`Content-Type`(MediaType.application.json))

  private def bodyJson(response: Response[IO]): IO[Json] =
    response.bodyText.compile.string.map(raw => parse(raw).fold(throw _, identity))

  private def stringAt(json: Json, field: String): Option[String] =
    json.hcursor.get[String](field).toOption

  private def items(json: Json): List[Json] =
    json.hcursor.downField("items").values.map(_.toList).getOrElse(Nil)

  /**
   * Resultado por identificador de alerta. Es un mapa a propósito: dos alertas registradas en el
   * mismo instante comparten `createdAt` y su orden relativo en el listado (desempate por
   * identificador aleatorio) no es el de creación, así que las pruebas no deben depender de él.
   */
  private def outcomes(json: Json): Map[String, String] =
    items(json).flatMap { item =>
      for
        id      <- item.hcursor.downField("alert").get[String]("id").toOption
        outcome <- stringAt(item, "outcome")
      yield id -> outcome
    }.toMap

  private def assertProblem(response: Response[IO], status: Status): Unit =
    assertEquals(response.status, status)
    val contentType = response.headers.get(ci"Content-Type").map(_.head.value)
    assert(
      contentType.exists(_.startsWith("application/problem+json")),
      s"Content-Type inesperado: $contentType"
    )

  /** Registra una alerta a través del propio endpoint y devuelve su identificador. */
  private def createAlert(
      app: HttpApp[IO],
      clientId: String,
      threshold: String,
      direction: String
  ): IO[String] =
    val body = Json.obj(
      "series"    -> "PD04640PD".asJson,
      "threshold" -> Json.fromBigDecimal(BigDecimal(threshold)),
      "direction" -> direction.asJson
    )
    for
      response <- app.run(jsonRequest(Method.POST, alertsUri, body).authenticatedAs(clientId))
      _ = assertEquals(response.status, Status.Created)
      json <- bodyJson(response)
    yield stringAt(json, "id").getOrElse(fail("La respuesta no incluye id"))

  private def deactivate(
      app: HttpApp[IO],
      clientId: String,
      id: String,
      threshold: String,
      direction: String
  ): IO[Unit] =
    val body = Json.obj(
      "series"    -> "PD04640PD".asJson,
      "threshold" -> Json.fromBigDecimal(BigDecimal(threshold)),
      "direction" -> direction.asJson,
      "status"    -> "INACTIVE".asJson
    )
    app
      .run(
        jsonRequest(Method.PUT, Uri.unsafeFromString(s"$alertsUri/$id"), body)
          .authenticatedAs(clientId)
      )
      .map(response => assertEquals(response.status, Status.Ok))

  /**
   * Cuatro alertas del propietario frente a 3.523: dos disparadas, una no y una inactiva. Devuelve
   * los identificadores.
   */
  private def ownerAlerts(app: HttpApp[IO]): IO[List[String]] =
    for
      above350 <- createAlert(app, owner, "3.50", "ABOVE")
      below360 <- createAlert(app, owner, "3.60", "BELOW")
      above360 <- createAlert(app, owner, "3.60", "ABOVE")
      inactive <- createAlert(app, owner, "3.00", "ABOVE")
      _        <- deactivate(app, owner, inactive, "3.00", "ABOVE")
    yield List(above350, below360, above360, inactive)

  // --- Camino feliz ----------------------------------------------------------------------------

  test("responde 200 con el dato usado, base concluyente y el resultado de cada alerta"):
    val retrievedAt = Instant.now().minusSeconds(90)
    withApp(StubExchangeRateSource.fixed(StubExchangeRateSource.snapshotAt(retrievedAt))) { app =>
      for
        ids      <- ownerAlerts(app)
        response <- app.run(evaluation())
        json     <- bodyJson(response)
      yield
        assertEquals(response.status, Status.Ok)
        assertEquals(stringAt(json, "basis"), Some("OFFICIAL_CONFIRMED"))
        assertEquals(json.hcursor.get[Boolean]("conclusive").toOption, Some(true))
        assert(stringAt(json, "evaluatedAt").exists(_.nonEmpty))
        val rate = json.hcursor.downField("rate")
        assertEquals(rate.get[BigDecimal]("value").toOption, Some(BigDecimal("3.523")))
        assertEquals(rate.get[String]("series").toOption, Some("PD04640PD"))
        assertEquals(rate.get[String]("freshness").toOption, Some("FRESH"))
        assertEquals(rate.get[String]("retrievedAt").toOption, Some(retrievedAt.toString))
        assert(rate.get[Long]("ageSeconds").exists(_ >= 90L))
        assertEquals(rate.downField("source").get[String]("id").toOption, Some("BCRP"))
        assertEquals(rate.downField("source").get[Boolean]("official").toOption, Some(true))
        assertEquals(
          outcomes(json),
          Map(
            ids(0) -> "TRIGGERED",
            ids(1) -> "TRIGGERED",
            ids(2) -> "NOT_TRIGGERED",
            ids(3) -> "INACTIVE"
          )
        )
        // Cada elemento anida la representación habitual de la alerta; se localiza por id porque
        // el orden entre alertas con el mismo createdAt no es el de creación.
        val first = items(json)
          .map(_.hcursor.downField("alert"))
          .find(_.get[String]("id").toOption.contains(ids(0)))
          .getOrElse(fail(s"items no contiene la alerta ${ids(0)}"))
        assertEquals(first.get[String]("clientId").toOption, Some(owner))
        assertEquals(first.get[BigDecimal]("threshold").toOption, Some(BigDecimal("3.5")))
        assertEquals(first.get[String]("direction").toOption, Some("ABOVE"))
        assertEquals(first.get[String]("status").toOption, Some("ACTIVE"))
    }

  test("el bloque rate es la misma representación que GET /api/v1/rates/current"):
    val snapshot = StubExchangeRateSource.snapshotAt(Instant.now().minusSeconds(3600))
    withApp(StubExchangeRateSource.fixed(snapshot)) { app =>
      for
        evaluated <- app.run(evaluation()).flatMap(bodyJson)
        current   <- app.run(Request[IO](Method.GET, currentUri).authenticatedAs(owner))
        rate      <- bodyJson(current)
      yield
        // `ageSeconds` se calcula en cada respuesta y puede diferir en un segundo entre llamadas.
        def withoutAge(json: Json): Json = json.mapObject(_.remove("ageSeconds"))
        assertEquals(
          evaluated.hcursor.downField("rate").focus.map(withoutAge),
          Some(withoutAge(rate))
        )
    }

  test("GET /api/v1/alerts/evaluation no se confunde con GET /api/v1/alerts/{id}"):
    withApp(StubExchangeRateSource.freshSample) { app =>
      app.run(evaluation()).map { response =>
        assertEquals(response.status, Status.Ok)
        assert(
          response.headers.get(ci"Content-Type").exists(_.head.value.startsWith("application/json"))
        )
      }
    }

  test("un cliente sin alertas recibe 200 con items vacío y el dato usado, no 404"):
    withApp(StubExchangeRateSource.freshSample) { app =>
      for
        response <- app.run(evaluation("cliente-999"))
        json     <- bodyJson(response)
      yield
        assertEquals(response.status, Status.Ok)
        assertEquals(items(json), Nil)
        assertEquals(stringAt(json, "basis"), Some("OFFICIAL_CONFIRMED"))
        assertEquals(
          json.hcursor.downField("rate").get[BigDecimal]("value").toOption,
          Some(BigDecimal("3.523"))
        )
    }

  // --- Aislamiento por cliente -----------------------------------------------------------------

  test("evalúa únicamente las alertas del sujeto del token"):
    withApp(StubExchangeRateSource.freshSample) { app =>
      for
        ids     <- ownerAlerts(app)
        foreign <- createAlert(app, other, "3.00", "ABOVE") // se dispararía si se evaluara
        mine    <- app.run(evaluation(owner)).flatMap(bodyJson)
        theirs  <- app.run(evaluation(other)).flatMap(bodyJson)
      yield
        assertEquals(outcomes(mine).keySet, ids.toSet)
        assert(!outcomes(mine).contains(foreign))
        assertEquals(outcomes(theirs), Map(foreign -> "TRIGGERED"))
    }

  // --- Calidad del dato ------------------------------------------------------------------------

  test("sobre el respaldo no oficial: MARKET_REFERENCE, no concluyente, fuente no oficial"):
    withApp(StubExchangeRateSource.freshSampleFrom(RateProvider.ExchangeRateApi)) { app =>
      for
        above350 <- createAlert(app, owner, "3.50", "ABOVE")
        below360 <- createAlert(app, owner, "3.60", "BELOW")
        response <- app.run(evaluation())
        json     <- bodyJson(response)
      yield
        assertEquals(response.status, Status.Ok)
        assertEquals(stringAt(json, "basis"), Some("MARKET_REFERENCE"))
        assertEquals(json.hcursor.get[Boolean]("conclusive").toOption, Some(false))
        val rate = json.hcursor.downField("rate")
        assertEquals(rate.get[BigDecimal]("value").toOption, Some(BigDecimal("3.350827")))
        assertEquals(rate.get[String]("freshness").toOption, Some("FRESH"))
        assertEquals(rate.downField("source").get[String]("id").toOption, Some("ERAPI"))
        assertEquals(rate.downField("source").get[Boolean]("official").toOption, Some(false))
        assert(
          rate
            .downField("source")
            .get[String]("attribution")
            .exists(_.contains("Rates By Exchange Rate API"))
        )
        assertEquals(
          outcomes(json),
          Map(above350 -> "NOT_TRIGGERED", below360 -> "TRIGGERED")
        )
    }

  test("sobre un dato STALE: UNCONFIRMED y no concluyente, sea oficial o no la fuente"):
    List(RateProvider.Bcrp, RateProvider.ExchangeRateApi).foldLeft(IO.unit) {
      (previous, provider) =>
        val snapshot = StubExchangeRateSource.snapshotAt(Instant.now(), Freshness.Stale, provider)
        previous *> withApp(StubExchangeRateSource.fixed(snapshot)) { app =>
          for
            id       <- createAlert(app, owner, "3.00", "ABOVE")
            response <- app.run(evaluation())
            json     <- bodyJson(response)
          yield
            assertEquals(response.status, Status.Ok)
            assertEquals(stringAt(json, "basis"), Some("UNCONFIRMED"), provider.toString)
            assertEquals(json.hcursor.get[Boolean]("conclusive").toOption, Some(false))
            assertEquals(
              json.hcursor.downField("rate").get[String]("freshness").toOption,
              Some("STALE")
            )
            assertEquals(
              json.hcursor.downField("rate").downField("source").get[Boolean]("official").toOption,
              Some(provider.official)
            )
            assertEquals(outcomes(json), Map(id -> "TRIGGERED"))
        }
    }

  // --- Sin tipo de cambio ----------------------------------------------------------------------

  test("sin ninguna fuente disponible responde el mismo 503 Problem Details que /rates/current"):
    withApp(StubExchangeRateSource.failing(ExchangeRateUnavailable(BcrpSeries.UsdPenSbsSell))) {
      app =>
        for
          _          <- createAlert(app, owner, "3.00", "ABOVE")
          response   <- app.run(evaluation())
          json       <- bodyJson(response)
          current    <- app.run(Request[IO](Method.GET, currentUri).authenticatedAs(owner))
          currentRef <- bodyJson(current)
        yield
          assertProblem(response, Status.ServiceUnavailable)
          assertProblem(current, Status.ServiceUnavailable)
          assertEquals(json, currentRef)
          assertEquals(stringAt(json, "type"), Some("urn:fx-alerts:problem:source-unavailable"))
          assertEquals(json.hcursor.get[Int]("status").toOption, Some(503))
    }

  test("sin dato publicado responde el mismo 404 Problem Details que /rates/current"):
    withApp(StubExchangeRateSource.failing(ExchangeRateNotPublished(BcrpSeries.UsdPenSbsSell))) {
      app =>
        for
          response   <- app.run(evaluation())
          json       <- bodyJson(response)
          current    <- app.run(Request[IO](Method.GET, currentUri).authenticatedAs(owner))
          currentRef <- bodyJson(current)
        yield
          assertProblem(response, Status.NotFound)
          assertEquals(json, currentRef)
    }

  // --- Autenticación y autorización ------------------------------------------------------------

  test("sin token o con token inválido responde 401, sin consultar la fuente"):
    withApp(IO.raiseError(new AssertionError("La fuente no debe consultarse"))) { app =>
      for
        missing <- app.run(Request[IO](Method.GET, evaluationUri))
        invalid <- app.run(
          Request[IO](Method.GET, evaluationUri).withBearer(TestTokens.foreign(owner))
        )
      yield
        assertProblem(missing, Status.Unauthorized)
        assertProblem(invalid, Status.Unauthorized)
        assertEquals(
          missing.headers.get(ci"WWW-Authenticate").map(_.head.value),
          Some("Bearer realm=\"bcrp-fx-alerts\"")
        )
    }

  test("exige alerts:read: basta por sí solo, y alerts:write o rates:read responden 403"):
    withApp(StubExchangeRateSource.freshSample) { app =>
      for
        readOnly  <- app.run(evaluation(owner, Scope.AlertsRead))
        writeOnly <- app.run(evaluation(owner, Scope.AlertsWrite))
        ratesOnly <- app.run(evaluation(owner, Scope.RatesRead))
        json      <- bodyJson(writeOnly)
      yield
        assertEquals(readOnly.status, Status.Ok)
        assertProblem(writeOnly, Status.Forbidden)
        assertProblem(ratesOnly, Status.Forbidden)
        assertEquals(
          writeOnly.headers.get(ci"WWW-Authenticate").map(_.head.value),
          Some(
            "Bearer realm=\"bcrp-fx-alerts\", error=\"insufficient_scope\", scope=\"alerts:read\""
          )
        )
        assertEquals(stringAt(json, "type"), Some("urn:fx-alerts:problem:forbidden"))
    }

  // --- Documentación ---------------------------------------------------------------------------

  test("el documento OpenAPI documenta el endpoint, sus valores y sus ejemplos"):
    withApp(StubExchangeRateSource.freshSample) { app =>
      for
        response <- app.run(Request[IO](Method.GET, uri"/docs/docs.yaml"))
        document <- response.bodyText.compile.string
      yield
        assertEquals(response.status, Status.Ok)
        assert(document.contains("/api/v1/alerts/evaluation"))
        List("TRIGGERED", "NOT_TRIGGERED", "INACTIVE", "SERIES_MISMATCH").foreach { value =>
          assert(document.contains(value), s"Falta el resultado $value")
        }
        List("OFFICIAL_CONFIRMED", "MARKET_REFERENCE", "UNCONFIRMED").foreach { value =>
          assert(document.contains(value), s"Falta la base $value")
        }
        assert(document.contains("conclusive"))
        assert(document.contains("Precio oficial confirmado (concluyente)"))
        assert(document.contains("Referencia de mercado no oficial (no concluyente)"))
        assert(document.contains("Dato sin confirmar, ninguna fuente responde (no concluyente)"))
        assert(document.contains("Cliente sin alertas"))
        assert(document.contains("estrictamente mayor"))
    }
