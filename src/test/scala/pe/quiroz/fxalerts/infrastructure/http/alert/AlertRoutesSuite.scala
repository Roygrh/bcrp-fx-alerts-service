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
import pe.quiroz.fxalerts.application.alert.{AlertService, InMemoryAlertRepository}
import pe.quiroz.fxalerts.infrastructure.http.HttpApi

/**
 * Pruebas de las rutas de alertas sin base de datos: el servicio se monta sobre
 * [[InMemoryAlertRepository]] y la aplicación con la misma configuración que producción
 * (manejadores Problem Details y middleware incluidos).
 */
class AlertRoutesSuite extends CatsEffectSuite:

  private given Logger[IO] = Slf4jLogger.getLogger[IO]

  private val alertsUri = uri"/api/v1/alerts"

  private val unknownIdUri = uri"/api/v1/alerts/00000000-0000-0000-0000-000000000000"

  private val createBody = Json.obj(
    "clientId"  -> "cliente-001".asJson,
    "series"    -> "PD04640PD".asJson,
    "threshold" -> Json.fromBigDecimal(BigDecimal("3.85")),
    "direction" -> "ABOVE".asJson
  )

  private val updateBody = Json.obj(
    "series"    -> "PD04640PD".asJson,
    "threshold" -> Json.fromBigDecimal(BigDecimal("3.95")),
    "direction" -> "BELOW".asJson,
    "status"    -> "INACTIVE".asJson
  )

  private def withApp[A](body: HttpApp[IO] => IO[A]): IO[A] =
    InMemoryAlertRepository.empty.flatMap { repository =>
      val routes = AlertRoutes[IO](AlertService[IO](repository))
      body(HttpApi.fromEndpoints[IO](routes.serverEndpoints))
    }

  private def jsonRequest(method: Method, uri: Uri, body: Json): Request[IO] =
    Request[IO](method, uri)
      .withEntity(body.noSpaces)
      .withContentType(`Content-Type`(MediaType.application.json))

  private def bodyJson(response: Response[IO]): IO[Json] =
    response.bodyText.compile.string.map(raw => parse(raw).fold(throw _, identity))

  private def stringAt(json: Json, field: String): Option[String] =
    json.hcursor.get[String](field).toOption

  private def firstError(json: Json): (Option[String], Option[String]) =
    val cursor = json.hcursor.downField("errors").downArray
    (cursor.get[String]("field").toOption, cursor.get[String]("message").toOption)

  private def assertProblem(response: Response[IO], status: Status): Unit =
    assertEquals(response.status, status)
    val contentType = response.headers.get(ci"Content-Type").map(_.head.value)
    assert(
      contentType.exists(_.startsWith("application/problem+json")),
      s"Content-Type inesperado: $contentType"
    )

  /** Crea una alerta a través del propio endpoint y devuelve su `Location` y su representación. */
  private def createAlert(app: HttpApp[IO], clientId: String = "cliente-001"): IO[(Uri, Json)] =
    val body = createBody.mapObject(_.add("clientId", clientId.asJson))
    for
      response <- app.run(jsonRequest(Method.POST, alertsUri, body))
      _        = assertEquals(response.status, Status.Created)
      location = response.headers
        .get(ci"Location")
        .map(_.head.value)
        .getOrElse(fail("La respuesta 201 no incluye la cabecera Location"))
      json <- bodyJson(response)
    yield (Uri.unsafeFromString(location), json)

  // --- Camino feliz ----------------------------------------------------------------------------

  test("POST /api/v1/alerts responde 201 con Location y la representación creada"):
    withApp { app =>
      for
        response <- app.run(jsonRequest(Method.POST, alertsUri, createBody))
        json     <- bodyJson(response)
      yield
        assertEquals(response.status, Status.Created)
        val id = stringAt(json, "id").getOrElse(fail("La respuesta no incluye id"))
        assertEquals(
          response.headers.get(ci"Location").map(_.head.value),
          Some(s"/api/v1/alerts/$id")
        )
        assertEquals(stringAt(json, "clientId"), Some("cliente-001"))
        assertEquals(stringAt(json, "series"), Some("PD04640PD"))
        assertEquals(json.hcursor.get[BigDecimal]("threshold").toOption, Some(BigDecimal("3.85")))
        assertEquals(stringAt(json, "direction"), Some("ABOVE"))
        assertEquals(stringAt(json, "status"), Some("ACTIVE"))
        assertEquals(stringAt(json, "createdAt"), stringAt(json, "updatedAt"))
    }

  test("GET /api/v1/alerts responde 200 con todas las alertas"):
    withApp { app =>
      for
        _        <- createAlert(app, "cliente-001")
        _        <- createAlert(app, "cliente-002")
        response <- app.run(Request[IO](Method.GET, alertsUri))
        json     <- bodyJson(response)
      yield
        assertEquals(response.status, Status.Ok)
        assertEquals(json.hcursor.downField("items").values.map(_.size), Some(2))
    }

  test("GET /api/v1/alerts?clientId=... filtra por cliente"):
    withApp { app =>
      for
        _        <- createAlert(app, "cliente-001")
        _        <- createAlert(app, "cliente-002")
        filtered <- app.run(
          Request[IO](Method.GET, alertsUri.withQueryParam("clientId", "cliente-002"))
        )
        json  <- bodyJson(filtered)
        empty <- app.run(
          Request[IO](Method.GET, alertsUri.withQueryParam("clientId", "cliente-999"))
        )
        emptyJson <- bodyJson(empty)
      yield
        assertEquals(filtered.status, Status.Ok)
        val items = json.hcursor.downField("items").values.map(_.toList).getOrElse(Nil)
        assertEquals(items.size, 1)
        assertEquals(items.headOption.flatMap(stringAt(_, "clientId")), Some("cliente-002"))
        assertEquals(emptyJson.hcursor.downField("items").values.map(_.size), Some(0))
    }

  test("GET /api/v1/alerts/{id} responde 200 con la alerta pedida"):
    withApp { app =>
      for
        createdResult <- createAlert(app)
        (location, created) = createdResult
        response <- app.run(Request[IO](Method.GET, location))
        json     <- bodyJson(response)
      yield
        assertEquals(response.status, Status.Ok)
        assertEquals(json, created)
    }

  test("PUT /api/v1/alerts/{id} responde 200 con la configuración reemplazada"):
    withApp { app =>
      for
        createdResult <- createAlert(app)
        (location, created) = createdResult
        response <- app.run(jsonRequest(Method.PUT, location, updateBody))
        json     <- bodyJson(response)
      yield
        assertEquals(response.status, Status.Ok)
        assertEquals(stringAt(json, "id"), stringAt(created, "id"))
        assertEquals(stringAt(json, "clientId"), Some("cliente-001"))
        assertEquals(json.hcursor.get[BigDecimal]("threshold").toOption, Some(BigDecimal("3.95")))
        assertEquals(stringAt(json, "direction"), Some("BELOW"))
        assertEquals(stringAt(json, "status"), Some("INACTIVE"))
        assertEquals(stringAt(json, "createdAt"), stringAt(created, "createdAt"))
    }

  test("DELETE /api/v1/alerts/{id} responde 204 y la alerta deja de existir"):
    withApp { app =>
      for
        createdResult <- createAlert(app)
        (location, _) = createdResult
        deleted     <- app.run(Request[IO](Method.DELETE, location))
        deletedBody <- deleted.bodyText.compile.string
        afterwards  <- app.run(Request[IO](Method.GET, location))
      yield
        assertEquals(deleted.status, Status.NoContent)
        assertEquals(deletedBody, "")
        assertEquals(afterwards.status, Status.NotFound)
    }

  // --- 404 sobre identificadores inexistentes --------------------------------------------------

  test("GET, PUT y DELETE sobre un id inexistente responden 404 Problem Details"):
    withApp { app =>
      for
        get    <- app.run(Request[IO](Method.GET, unknownIdUri))
        json   <- bodyJson(get)
        put    <- app.run(jsonRequest(Method.PUT, unknownIdUri, updateBody))
        delete <- app.run(Request[IO](Method.DELETE, unknownIdUri))
      yield
        assertProblem(get, Status.NotFound)
        assertProblem(put, Status.NotFound)
        assertProblem(delete, Status.NotFound)
        assertEquals(stringAt(json, "type"), Some("urn:fx-alerts:problem:not-found"))
        assertEquals(stringAt(json, "title"), Some("Recurso no encontrado"))
        assertEquals(json.hcursor.get[Int]("status").toOption, Some(404))
        assert(stringAt(json, "detail").exists(_.nonEmpty))
    }

  // --- 400 por violaciones de reglas de negocio ------------------------------------------------

  test("POST con umbral inválido responde 400 Problem Details con el error del campo"):
    withApp { app =>
      val body = createBody.mapObject(_.add("threshold", Json.fromBigDecimal(BigDecimal(0))))
      for
        response <- app.run(jsonRequest(Method.POST, alertsUri, body))
        json     <- bodyJson(response)
      yield
        assertProblem(response, Status.BadRequest)
        assertEquals(stringAt(json, "type"), Some("urn:fx-alerts:problem:validation"))
        assertEquals(json.hcursor.get[Int]("status").toOption, Some(400))
        val (field, message) = firstError(json)
        assertEquals(field, Some("threshold"))
        assert(message.exists(_.nonEmpty))
    }

  test("POST con clientId vacío responde 400 Problem Details con el error del campo"):
    withApp { app =>
      val body = createBody.mapObject(_.add("clientId", "   ".asJson))
      for
        response <- app.run(jsonRequest(Method.POST, alertsUri, body))
        json     <- bodyJson(response)
      yield
        assertProblem(response, Status.BadRequest)
        assertEquals(stringAt(json, "type"), Some("urn:fx-alerts:problem:validation"))
        assertEquals(firstError(json)._1, Some("clientId"))
    }

  test("GET /api/v1/alerts?clientId= (vacío) responde 400 en lugar de una lista vacía"):
    withApp { app =>
      for
        response <- app.run(Request[IO](Method.GET, alertsUri.withQueryParam("clientId", "")))
        json     <- bodyJson(response)
      yield
        assertProblem(response, Status.BadRequest)
        assertEquals(firstError(json)._1, Some("clientId"))
    }

  // --- 400 por violaciones del protocolo -------------------------------------------------------

  test("GET con un id que no es UUID responde 400 Problem Details"):
    withApp { app =>
      for
        response <- app.run(Request[IO](Method.GET, uri"/api/v1/alerts/no-es-un-uuid"))
        json     <- bodyJson(response)
      yield
        assertProblem(response, Status.BadRequest)
        assertEquals(stringAt(json, "type"), Some("urn:fx-alerts:problem:malformed-request"))
        assertEquals(firstError(json)._1, Some("id"))
    }

  // --- Documentación ---------------------------------------------------------------------------

  test("el documento OpenAPI se genera con los endpoints de alertas (GET /docs/docs.yaml)"):
    withApp { app =>
      for
        response <- app.run(Request[IO](Method.GET, uri"/docs/docs.yaml"))
        document <- response.bodyText.compile.string
      yield
        assertEquals(response.status, Status.Ok)
        assert(document.contains("/api/v1/alerts"))
        assert(document.contains("application/problem+json"))
    }

  test("POST con un valor de enumerado no admitido responde 400 con el campo señalado"):
    withApp { app =>
      val body = createBody.mapObject(_.add("direction", "SIDEWAYS".asJson))
      for
        response <- app.run(jsonRequest(Method.POST, alertsUri, body))
        json     <- bodyJson(response)
      yield
        assertProblem(response, Status.BadRequest)
        assertEquals(stringAt(json, "type"), Some("urn:fx-alerts:problem:malformed-request"))
        assertEquals(firstError(json)._1, Some("direction"))
    }
