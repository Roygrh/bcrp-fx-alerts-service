package pe.quiroz.fxalerts.infrastructure.http.alert

import cats.effect.IO
import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import munit.CatsEffectSuite
import org.http4s.headers.`Content-Type`
import org.http4s.implicits.*
import org.http4s.{Header, HttpApp, MediaType, Method, Request, Response, Status, Uri}
import org.typelevel.ci.*
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import pe.quiroz.fxalerts.application.alert.{
  AlertEvaluationService,
  AlertService,
  InMemoryAlertRepository
}
import pe.quiroz.fxalerts.application.rate.{ExchangeRateService, StubExchangeRateSource}
import pe.quiroz.fxalerts.application.security.Scope
import pe.quiroz.fxalerts.infrastructure.http.HttpApi
import pe.quiroz.fxalerts.infrastructure.http.auth.TestTokens
import pe.quiroz.fxalerts.infrastructure.http.auth.TestTokens.*

/**
 * Pruebas de las rutas de alertas sin base de datos: el servicio se monta sobre
 * [[InMemoryAlertRepository]] y la aplicación con la misma configuración que producción
 * (manejadores Problem Details, seguridad y middleware incluidos). Los tokens se firman con las
 * claves de prueba generadas en tiempo de ejecución.
 */
class AlertRoutesSuite extends CatsEffectSuite:

  private given Logger[IO] = Slf4jLogger.getLogger[IO]

  private val alertsUri = uri"/api/v1/alerts"

  private val unknownIdUri = uri"/api/v1/alerts/00000000-0000-0000-0000-000000000000"

  private val owner = "cliente-001"
  private val other = "cliente-002"

  private val createBody = Json.obj(
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
    for
      repository <- InMemoryAlertRepository.empty
      source     <- StubExchangeRateSource(StubExchangeRateSource.freshSample)
      routes = AlertRoutes[IO](
        AlertService[IO](repository),
        AlertEvaluationService[IO](repository, ExchangeRateService[IO](source)),
        TestTokens.auth
      )
      result <- body(HttpApi.fromEndpoints[IO](routes.serverEndpoints))
    yield result

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

  private def header(response: Response[IO], name: String): Option[String] =
    response.headers.get(CIString(name)).map(_.head.value)

  private def assertProblem(response: Response[IO], status: Status): Unit =
    assertEquals(response.status, status)
    val contentType = header(response, "Content-Type")
    assert(
      contentType.exists(_.startsWith("application/problem+json")),
      s"Content-Type inesperado: $contentType"
    )

  /** Crea una alerta a través del propio endpoint y devuelve su `Location` y su representación. */
  private def createAlert(app: HttpApp[IO], clientId: String = owner): IO[(Uri, Json)] =
    for
      response <- app.run(jsonRequest(Method.POST, alertsUri, createBody).authenticatedAs(clientId))
      _        = assertEquals(response.status, Status.Created)
      location = header(response, "Location")
        .getOrElse(fail("La respuesta 201 no incluye la cabecera Location"))
      json <- bodyJson(response)
    yield (Uri.unsafeFromString(location), json)

  // --- Camino feliz ----------------------------------------------------------------------------

  test("POST /api/v1/alerts responde 201 con Location y la alerta a nombre del cliente del token"):
    withApp { app =>
      for
        response <- app.run(jsonRequest(Method.POST, alertsUri, createBody).authenticatedAs(owner))
        json     <- bodyJson(response)
      yield
        assertEquals(response.status, Status.Created)
        val id = stringAt(json, "id").getOrElse(fail("La respuesta no incluye id"))
        assertEquals(header(response, "Location"), Some(s"/api/v1/alerts/$id"))
        assertEquals(stringAt(json, "clientId"), Some(owner))
        assertEquals(stringAt(json, "series"), Some("PD04640PD"))
        assertEquals(json.hcursor.get[BigDecimal]("threshold").toOption, Some(BigDecimal("3.85")))
        assertEquals(stringAt(json, "direction"), Some("ABOVE"))
        assertEquals(stringAt(json, "status"), Some("ACTIVE"))
        assertEquals(stringAt(json, "createdAt"), stringAt(json, "updatedAt"))
    }

  test("POST ignora un clientId en el cuerpo: el propietario es siempre el sujeto del token"):
    withApp { app =>
      val body = createBody.mapObject(_.add("clientId", other.asJson))
      for
        response <- app.run(jsonRequest(Method.POST, alertsUri, body).authenticatedAs(owner))
        json     <- bodyJson(response)
      yield
        assertEquals(response.status, Status.Created)
        assertEquals(stringAt(json, "clientId"), Some(owner))
    }

  test("GET /api/v1/alerts responde 200 solo con las alertas del cliente autenticado"):
    withApp { app =>
      for
        _         <- createAlert(app, owner)
        _         <- createAlert(app, owner)
        _         <- createAlert(app, other)
        response  <- app.run(Request[IO](Method.GET, alertsUri).authenticatedAs(owner))
        json      <- bodyJson(response)
        empty     <- app.run(Request[IO](Method.GET, alertsUri).authenticatedAs("cliente-999"))
        emptyJson <- bodyJson(empty)
      yield
        assertEquals(response.status, Status.Ok)
        val items = json.hcursor.downField("items").values.map(_.toList).getOrElse(Nil)
        assertEquals(items.size, 2)
        assert(items.forall(stringAt(_, "clientId").contains(owner)))
        assertEquals(emptyJson.hcursor.downField("items").values.map(_.size), Some(0))
    }

  test("GET /api/v1/alerts/{id} responde 200 con la alerta pedida"):
    withApp { app =>
      for
        createdResult <- createAlert(app)
        (location, created) = createdResult
        response <- app.run(Request[IO](Method.GET, location).authenticatedAs(owner))
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
        response <- app.run(jsonRequest(Method.PUT, location, updateBody).authenticatedAs(owner))
        json     <- bodyJson(response)
      yield
        assertEquals(response.status, Status.Ok)
        assertEquals(stringAt(json, "id"), stringAt(created, "id"))
        assertEquals(stringAt(json, "clientId"), Some(owner))
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
        deleted     <- app.run(Request[IO](Method.DELETE, location).authenticatedAs(owner))
        deletedBody <- deleted.bodyText.compile.string
        afterwards  <- app.run(Request[IO](Method.GET, location).authenticatedAs(owner))
      yield
        assertEquals(deleted.status, Status.NoContent)
        assertEquals(deletedBody, "")
        assertEquals(afterwards.status, Status.NotFound)
    }

  // --- Aislamiento por cliente -----------------------------------------------------------------

  test("una alerta de otro cliente responde 404 en GET, PUT y DELETE, igual que una inexistente"):
    withApp { app =>
      for
        createdResult <- createAlert(app, owner)
        (location, _) = createdResult
        get         <- app.run(Request[IO](Method.GET, location).authenticatedAs(other))
        getJson     <- bodyJson(get)
        put         <- app.run(jsonRequest(Method.PUT, location, updateBody).authenticatedAs(other))
        delete      <- app.run(Request[IO](Method.DELETE, location).authenticatedAs(other))
        unknown     <- app.run(Request[IO](Method.GET, unknownIdUri).authenticatedAs(other))
        unknownJson <- bodyJson(unknown)
        stillThere  <- app.run(Request[IO](Method.GET, location).authenticatedAs(owner))
        stillJson   <- bodyJson(stillThere)
      yield
        assertProblem(get, Status.NotFound)
        assertProblem(put, Status.NotFound)
        assertProblem(delete, Status.NotFound)
        // Mismo tipo, título y estado que un id inexistente; el detalle solo difiere en el id.
        List("type", "title", "status").foreach { field =>
          assertEquals(
            getJson.hcursor.downField(field).focus,
            unknownJson.hcursor.downField(field).focus
          )
        }
        assert(stringAt(getJson, "detail").exists(_.startsWith("No existe la alerta con id")))
        assertEquals(stillThere.status, Status.Ok)
        assertEquals(stringAt(stillJson, "status"), Some("ACTIVE"))
    }

  // --- Autenticación y autorización ------------------------------------------------------------

  test("sin token responde 401 Problem Details con WWW-Authenticate Bearer"):
    withApp { app =>
      for
        response <- app.run(Request[IO](Method.GET, alertsUri))
        json     <- bodyJson(response)
        post     <- app.run(jsonRequest(Method.POST, alertsUri, createBody))
      yield
        assertProblem(response, Status.Unauthorized)
        assertProblem(post, Status.Unauthorized)
        assertEquals(header(response, "WWW-Authenticate"), Some("Bearer realm=\"bcrp-fx-alerts\""))
        assertEquals(stringAt(json, "type"), Some("urn:fx-alerts:problem:unauthorized"))
        assertEquals(json.hcursor.get[Int]("status").toOption, Some(401))
    }

  test("token caducado, con firma ajena o basura responden 401 con el mismo cuerpo"):
    withApp { app =>
      for
        missing     <- app.run(Request[IO](Method.GET, alertsUri))
        missingJson <- bodyJson(missing)
        expired <- app.run(Request[IO](Method.GET, alertsUri).withBearer(TestTokens.expired(owner)))
        expiredJson <- bodyJson(expired)
        foreign <- app.run(Request[IO](Method.GET, alertsUri).withBearer(TestTokens.foreign(owner)))
        foreignJson <- bodyJson(foreign)
        garbage     <- app.run(Request[IO](Method.GET, alertsUri).withBearer("abc.def.ghi"))
        garbageJson <- bodyJson(garbage)
      yield
        List(expired, foreign, garbage).foreach { response =>
          assertProblem(response, Status.Unauthorized)
          assertEquals(
            header(response, "WWW-Authenticate"),
            Some("Bearer realm=\"bcrp-fx-alerts\", error=\"invalid_token\"")
          )
        }
        assertEquals(expiredJson, missingJson)
        assertEquals(foreignJson, missingJson)
        assertEquals(garbageJson, missingJson)
    }

  test("una cabecera Authorization con esquema Basic responde 401 Problem Details"):
    withApp { app =>
      for response <- app.run(
          Request[IO](Method.GET, alertsUri)
            .putHeaders(Header.Raw(ci"Authorization", "Basic Y2xpZW50ZTpzZWNyZXRv"))
        )
      yield
        assertProblem(response, Status.Unauthorized)
        assert(header(response, "WWW-Authenticate").exists(_.startsWith("Bearer")))
    }

  test("con alerts:read se puede leer pero POST, PUT y DELETE responden 403"):
    withApp { app =>
      for
        createdResult <- createAlert(app)
        (location, _) = createdResult
        list <- app.run(Request[IO](Method.GET, alertsUri).authenticatedAs(owner, Scope.AlertsRead))
        get  <- app.run(Request[IO](Method.GET, location).authenticatedAs(owner, Scope.AlertsRead))
        post <- app.run(
          jsonRequest(Method.POST, alertsUri, createBody).authenticatedAs(owner, Scope.AlertsRead)
        )
        put <- app.run(
          jsonRequest(Method.PUT, location, updateBody).authenticatedAs(owner, Scope.AlertsRead)
        )
        delete <- app.run(
          Request[IO](Method.DELETE, location).authenticatedAs(owner, Scope.AlertsRead)
        )
        json <- bodyJson(post)
      yield
        assertEquals(list.status, Status.Ok)
        assertEquals(get.status, Status.Ok)
        assertProblem(post, Status.Forbidden)
        assertProblem(put, Status.Forbidden)
        assertProblem(delete, Status.Forbidden)
        assertEquals(
          header(post, "WWW-Authenticate"),
          Some(
            "Bearer realm=\"bcrp-fx-alerts\", error=\"insufficient_scope\", scope=\"alerts:write\""
          )
        )
        assertEquals(stringAt(json, "type"), Some("urn:fx-alerts:problem:forbidden"))
        assertEquals(json.hcursor.get[Int]("status").toOption, Some(403))
    }

  test("con alerts:write se puede escribir pero GET responde 403"):
    withApp { app =>
      for
        post <- app.run(
          jsonRequest(Method.POST, alertsUri, createBody).authenticatedAs(owner, Scope.AlertsWrite)
        )
        location = header(post, "Location")
          .map(Uri.unsafeFromString)
          .getOrElse(fail("Sin Location"))
        list <- app.run(
          Request[IO](Method.GET, alertsUri).authenticatedAs(owner, Scope.AlertsWrite)
        )
        get <- app.run(Request[IO](Method.GET, location).authenticatedAs(owner, Scope.AlertsWrite))
        put <- app.run(
          jsonRequest(Method.PUT, location, updateBody).authenticatedAs(owner, Scope.AlertsWrite)
        )
        delete <- app.run(
          Request[IO](Method.DELETE, location).authenticatedAs(owner, Scope.AlertsWrite)
        )
      yield
        assertEquals(post.status, Status.Created)
        assertProblem(list, Status.Forbidden)
        assertProblem(get, Status.Forbidden)
        assertEquals(put.status, Status.Ok)
        assertEquals(delete.status, Status.NoContent)
    }

  test("un token con rates:read únicamente no accede a las alertas"):
    withApp { app =>
      app.run(Request[IO](Method.GET, alertsUri).authenticatedAs(owner, Scope.RatesRead)).map {
        response =>
          assertProblem(response, Status.Forbidden)
      }
    }

  // --- 404 sobre identificadores inexistentes --------------------------------------------------

  test("GET, PUT y DELETE sobre un id inexistente responden 404 Problem Details"):
    withApp { app =>
      for
        get    <- app.run(Request[IO](Method.GET, unknownIdUri).authenticatedAs(owner))
        json   <- bodyJson(get)
        put    <- app.run(jsonRequest(Method.PUT, unknownIdUri, updateBody).authenticatedAs(owner))
        delete <- app.run(Request[IO](Method.DELETE, unknownIdUri).authenticatedAs(owner))
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
        response <- app.run(jsonRequest(Method.POST, alertsUri, body).authenticatedAs(owner))
        json     <- bodyJson(response)
      yield
        assertProblem(response, Status.BadRequest)
        assertEquals(stringAt(json, "type"), Some("urn:fx-alerts:problem:validation"))
        assertEquals(json.hcursor.get[Int]("status").toOption, Some(400))
        val (field, message) = firstError(json)
        assertEquals(field, Some("threshold"))
        assert(message.exists(_.nonEmpty))
    }

  // --- 400 por violaciones del protocolo -------------------------------------------------------

  test("GET con un id que no es UUID responde 400 Problem Details"):
    withApp { app =>
      for
        response <- app.run(
          Request[IO](Method.GET, uri"/api/v1/alerts/no-es-un-uuid").authenticatedAs(owner)
        )
        json <- bodyJson(response)
      yield
        assertProblem(response, Status.BadRequest)
        assertEquals(stringAt(json, "type"), Some("urn:fx-alerts:problem:malformed-request"))
        assertEquals(firstError(json)._1, Some("id"))
    }

  test("POST con un valor de enumerado no admitido responde 400 con el campo señalado"):
    withApp { app =>
      val body = createBody.mapObject(_.add("direction", "SIDEWAYS".asJson))
      for
        response <- app.run(jsonRequest(Method.POST, alertsUri, body).authenticatedAs(owner))
        json     <- bodyJson(response)
      yield
        assertProblem(response, Status.BadRequest)
        assertEquals(stringAt(json, "type"), Some("urn:fx-alerts:problem:malformed-request"))
        assertEquals(firstError(json)._1, Some("direction"))
    }

  test("la autenticación se comprueba antes que el cuerpo: sin token, un JSON inválido es 401"):
    withApp { app =>
      val body = createBody.mapObject(_.add("direction", "SIDEWAYS".asJson))
      app.run(jsonRequest(Method.POST, alertsUri, body)).map(assertProblem(_, Status.Unauthorized))
    }

  // --- Documentación ---------------------------------------------------------------------------

  test("el documento OpenAPI publica el esquema OAuth2 y el alcance de cada endpoint, sin token"):
    withApp { app =>
      for
        response <- app.run(Request[IO](Method.GET, uri"/docs/docs.yaml"))
        document <- response.bodyText.compile.string
      yield
        assertEquals(response.status, Status.Ok)
        assert(document.contains("/api/v1/alerts"))
        assert(document.contains("application/problem+json"))
        assert(document.contains("securitySchemes"))
        assert(document.contains("clientCredentials"))
        assert(document.contains("tokenUrl: /oauth/token"))
        assert(document.contains("- alerts:read"))
        assert(document.contains("- alerts:write"))
        assert(document.contains("'401'"))
        assert(document.contains("'403'"))
    }

  test("Swagger UI (/docs) sigue abierto sin token"):
    withApp { app =>
      app.run(Request[IO](Method.GET, uri"/docs")).map { response =>
        assert(response.status.code < 400, s"Estado inesperado: ${response.status}")
      }
    }
