package pe.quiroz.fxalerts.infrastructure.http.auth

import cats.data.NonEmptyList
import cats.effect.IO
import io.circe.Json
import io.circe.parser.parse
import munit.CatsEffectSuite
import org.http4s.headers.Authorization
import org.http4s.implicits.*
import org.http4s.{BasicCredentials, HttpApp, Method, Request, Response, Status, UrlForm}
import org.typelevel.ci.*
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import pe.quiroz.fxalerts.application.security.{RegisteredClient, Scope, TokenPolicy, TokenService}
import pe.quiroz.fxalerts.domain.alert.ClientId
import pe.quiroz.fxalerts.infrastructure.http.HttpApi
import pe.quiroz.fxalerts.infrastructure.security.{Pbkdf2SecretHasher, StaticClientRegistry}

import scala.concurrent.duration.*

/**
 * `POST /oauth/token` con derivación PBKDF2 real (pocas iteraciones) y firma RS256 real con las
 * claves de prueba; sin red ni base de datos.
 */
class TokenRoutesSuite extends CatsEffectSuite:

  private given Logger[IO] = Slf4jLogger.getLogger[IO]

  private val tokenUri = uri"/oauth/token"

  private val hasher = Pbkdf2SecretHasher[IO](iterations = 1_000)

  private val clients: NonEmptyList[RegisteredClient] =
    NonEmptyList.of(
      RegisteredClient(
        ClientId.from("cliente-001").toOption.get,
        Pbkdf2SecretHasher.hashWith("secreto-001", Array.fill[Byte](16)(1), 1_000),
        Set(Scope.AlertsRead, Scope.AlertsWrite)
      ),
      RegisteredClient(
        ClientId.from("monitor-001").toOption.get,
        Pbkdf2SecretHasher.hashWith("secreto-monitor", Array.fill[Byte](16)(2), 1_000),
        Set(Scope.RatesRead)
      )
    )

  private val app: IO[HttpApp[IO]] =
    TokenService[IO](
      StaticClientRegistry[IO](clients),
      hasher,
      TestTokens.tokens,
      TokenPolicy(TestTokens.issuer, TestTokens.audience, 15.minutes)
    ).map(service => HttpApi.fromEndpoints[IO](TokenRoutes[IO](service).serverEndpoints))

  private def form(fields: (String, String)*): Request[IO] =
    Request[IO](Method.POST, tokenUri).withEntity(UrlForm(fields*))

  private def withBasic(request: Request[IO], clientId: String, secret: String): Request[IO] =
    request.putHeaders(Authorization(BasicCredentials(clientId, secret)))

  private def bodyJson(response: Response[IO]): IO[Json] =
    response.bodyText.compile.string.map(raw => parse(raw).fold(throw _, identity))

  private def stringAt(json: Json, field: String): Option[String] =
    json.hcursor.get[String](field).toOption

  private def header(response: Response[IO], name: String): Option[String] =
    response.headers.get(CIString(name)).map(_.head.value)

  private def assertTokenError(response: Response[IO], status: Status, code: String)(
      json: Json
  ): Unit =
    assertEquals(response.status, status)
    assert(header(response, "Content-Type").exists(_.startsWith("application/json")))
    assertEquals(stringAt(json, "error"), Some(code))
    val description = stringAt(json, "error_description").getOrElse(fail("Sin error_description"))
    assert(description.forall(c => c >= ' ' && c <= '~' && c != '"' && c != '\\'), description)

  test("credenciales en el cuerpo: 200 con access_token, token_type, expires_in y scope"):
    app.flatMap { app =>
      for
        response <- app.run(
          form(
            "grant_type"    -> "client_credentials",
            "client_id"     -> "cliente-001",
            "client_secret" -> "secreto-001"
          )
        )
        json     <- bodyJson(response)
        verified <- TestTokens.tokens.verify(stringAt(json, "access_token").getOrElse(""))
      yield
        assertEquals(response.status, Status.Ok)
        assertEquals(header(response, "Cache-Control"), Some("no-store"))
        assertEquals(header(response, "Pragma"), Some("no-cache"))
        assertEquals(stringAt(json, "token_type"), Some("Bearer"))
        assertEquals(json.hcursor.get[Long]("expires_in").toOption, Some(900L))
        assertEquals(stringAt(json, "scope"), Some("alerts:read alerts:write"))
        val client = verified.getOrElse(fail(s"El token emitido no verifica: $verified"))
        assertEquals(client.clientId.value, "cliente-001")
        assertEquals(client.scopes, Set(Scope.AlertsRead, Scope.AlertsWrite))
    }

  test("credenciales por HTTP Basic y scope restringido"):
    app.flatMap { app =>
      for
        response <- app.run(
          withBasic(
            form("grant_type" -> "client_credentials", "scope" -> "alerts:read"),
            "cliente-001",
            "secreto-001"
          )
        )
        json <- bodyJson(response)
      yield
        assertEquals(response.status, Status.Ok)
        assertEquals(stringAt(json, "scope"), Some("alerts:read"))
    }

  test(
    "Basic más client_id coincidente en el cuerpo se tolera; con client_secret es invalid_request"
  ):
    app.flatMap { app =>
      for
        tolerated <- app.run(
          withBasic(
            form("grant_type" -> "client_credentials", "client_id" -> "cliente-001"),
            "cliente-001",
            "secreto-001"
          )
        )
        rejected <- app.run(
          withBasic(
            form("grant_type" -> "client_credentials", "client_secret" -> "secreto-001"),
            "cliente-001",
            "secreto-001"
          )
        )
        json <- bodyJson(rejected)
      yield
        assertEquals(tolerated.status, Status.Ok)
        assertTokenError(rejected, Status.BadRequest, "invalid_request")(json)
    }

  test("cliente desconocido: 401 invalid_client con WWW-Authenticate Basic"):
    app.flatMap { app =>
      for
        response <- app.run(
          form(
            "grant_type"    -> "client_credentials",
            "client_id"     -> "nadie",
            "client_secret" -> "secreto-001"
          )
        )
        json <- bodyJson(response)
      yield
        assertTokenError(response, Status.Unauthorized, "invalid_client")(json)
        assertEquals(header(response, "WWW-Authenticate"), Some("Basic realm=\"bcrp-fx-alerts\""))
    }

  test("secreto incorrecto: misma respuesta que el cliente desconocido"):
    app.flatMap { app =>
      for
        wrong <- app.run(withBasic(form("grant_type" -> "client_credentials"), "cliente-001", "x"))
        unknown     <- app.run(withBasic(form("grant_type" -> "client_credentials"), "nadie", "x"))
        wrongJson   <- bodyJson(wrong)
        unknownJson <- bodyJson(unknown)
      yield
        assertEquals(wrong.status, Status.Unauthorized)
        assertEquals(unknown.status, Status.Unauthorized)
        assertEquals(wrongJson, unknownJson)
    }

  test("sin credenciales: 401 invalid_client"):
    app.flatMap { app =>
      for
        response <- app.run(form("grant_type" -> "client_credentials"))
        json     <- bodyJson(response)
      yield assertTokenError(response, Status.Unauthorized, "invalid_client")(json)
    }

  test("Authorization con un esquema distinto de Basic: 401 invalid_client"):
    app.flatMap { app =>
      for
        response <- app.run(
          form("grant_type" -> "client_credentials")
            .putHeaders(org.http4s.Header.Raw(ci"Authorization", "Bearer lo-que-sea"))
        )
        json <- bodyJson(response)
      yield assertTokenError(response, Status.Unauthorized, "invalid_client")(json)
    }

  test("grant_type no soportado: 400 unsupported_grant_type"):
    app.flatMap { app =>
      for
        response <- app.run(
          withBasic(form("grant_type" -> "authorization_code"), "cliente-001", "secreto-001")
        )
        json <- bodyJson(response)
      yield assertTokenError(response, Status.BadRequest, "unsupported_grant_type")(json)
    }

  test("grant_type ausente: 400 invalid_request"):
    app.flatMap { app =>
      for
        response <- app.run(withBasic(form(), "cliente-001", "secreto-001"))
        json     <- bodyJson(response)
      yield assertTokenError(response, Status.BadRequest, "invalid_request")(json)
    }

  test("scope no concedido al cliente: 400 invalid_scope"):
    app.flatMap { app =>
      for
        response <- app.run(
          withBasic(
            form("grant_type" -> "client_credentials", "scope" -> "rates:read"),
            "cliente-001",
            "secreto-001"
          )
        )
        json <- bodyJson(response)
      yield
        assertTokenError(response, Status.BadRequest, "invalid_scope")(json)
        assert(stringAt(json, "error_description").exists(_.contains("rates:read")))
    }

  test("el documento OpenAPI describe el endpoint de token y sus errores"):
    app.flatMap { app =>
      for
        response <- app.run(Request[IO](Method.GET, uri"/docs/docs.yaml"))
        document <- response.bodyText.compile.string
      yield
        assertEquals(response.status, Status.Ok)
        assert(document.contains("/oauth/token"))
        assert(document.contains("application/x-www-form-urlencoded"))
        assert(document.contains("invalid_client"))
    }
