package pe.quiroz.fxalerts.infrastructure.http

import cats.data.NonEmptyList
import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.implicits.*
import org.http4s.{HttpApp, Method, Request, Status, UrlForm}
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import pe.quiroz.fxalerts.application.alert.{
  AlertEvaluationService,
  AlertService,
  InMemoryAlertRepository
}
import pe.quiroz.fxalerts.application.health.{DatabaseHealthCheck, HealthService}
import pe.quiroz.fxalerts.application.rate.{ExchangeRateService, StubExchangeRateSource}
import pe.quiroz.fxalerts.application.security.{RegisteredClient, Scope, TokenPolicy, TokenService}
import pe.quiroz.fxalerts.domain.alert.ClientId
import pe.quiroz.fxalerts.infrastructure.http.alert.AlertRoutes
import pe.quiroz.fxalerts.infrastructure.http.auth.{TestTokens, TokenRoutes}
import pe.quiroz.fxalerts.infrastructure.http.health.HealthRoutes
import pe.quiroz.fxalerts.infrastructure.http.rate.RateRoutes
import pe.quiroz.fxalerts.infrastructure.security.{Pbkdf2SecretHasher, StaticClientRegistry}

import scala.concurrent.duration.*

/**
 * La aplicación completa tal como la compone `Main`, con dobles en los puertos de salida: comprueba
 * qué rutas quedan abiertas y cuáles exigen token, que es una propiedad de la composición y no de
 * cada grupo de rutas.
 */
class HttpApiSuite extends CatsEffectSuite:

  private given Logger[IO] = Slf4jLogger.getLogger[IO]

  private val app: IO[HttpApp[IO]] =
    for
      repository <- InMemoryAlertRepository.empty
      source     <- StubExchangeRateSource(StubExchangeRateSource.freshSample)
      clients = NonEmptyList.one(
        RegisteredClient(
          ClientId.from("cliente-001").toOption.get,
          Pbkdf2SecretHasher.hashWith("secreto-001", Array.fill[Byte](16)(1), 1_000),
          Scope.values.toSet
        )
      )
      tokenService <- TokenService[IO](
        StaticClientRegistry[IO](clients),
        Pbkdf2SecretHasher[IO](iterations = 1_000),
        TestTokens.tokens,
        TokenPolicy(TestTokens.issuer, TestTokens.audience, 15.minutes)
      )
      databaseCheck = new DatabaseHealthCheck[IO]:
        def ping: IO[Unit] = IO.unit
      rateService = ExchangeRateService[IO](source)
    yield HttpApi.httpApp[IO](
      HealthRoutes[IO](HealthService[IO](databaseCheck, 1.second, rateService, 1.second)),
      TokenRoutes[IO](tokenService),
      AlertRoutes[IO](
        AlertService[IO](repository),
        AlertEvaluationService[IO](repository, rateService),
        TestTokens.auth
      ),
      RateRoutes[IO](rateService, TestTokens.auth),
      TestTokens.auth
    )

  test("/health, /docs y /oauth/token no exigen token"):
    app.flatMap { app =>
      for
        health <- app.run(Request[IO](Method.GET, uri"/health"))
        docs   <- app.run(Request[IO](Method.GET, uri"/docs/docs.yaml"))
        ui     <- app.run(Request[IO](Method.GET, uri"/docs"))
        token  <- app.run(
          Request[IO](Method.POST, uri"/oauth/token").withEntity(
            UrlForm(
              "grant_type"    -> "client_credentials",
              "client_id"     -> "cliente-001",
              "client_secret" -> "secreto-001"
            )
          )
        )
      yield
        assertEquals(health.status, Status.Ok)
        assertEquals(docs.status, Status.Ok)
        assert(ui.status.code < 400, s"/docs respondió ${ui.status}")
        assertEquals(token.status, Status.Ok)
    }

  test("las rutas de negocio exigen token"):
    app.flatMap { app =>
      for
        alerts     <- app.run(Request[IO](Method.GET, uri"/api/v1/alerts"))
        evaluation <- app.run(Request[IO](Method.GET, uri"/api/v1/alerts/evaluation"))
        rates      <- app.run(Request[IO](Method.GET, uri"/api/v1/rates/current"))
      yield
        assertEquals(alerts.status, Status.Unauthorized)
        assertEquals(evaluation.status, Status.Unauthorized)
        assertEquals(rates.status, Status.Unauthorized)
    }

  test("un token emitido por /oauth/token abre las rutas de negocio"):
    app.flatMap { app =>
      for
        issued <- app.run(
          Request[IO](Method.POST, uri"/oauth/token").withEntity(
            UrlForm(
              "grant_type"    -> "client_credentials",
              "client_id"     -> "cliente-001",
              "client_secret" -> "secreto-001"
            )
          )
        )
        body <- issued.bodyText.compile.string
        token = io.circe.parser
          .parse(body)
          .flatMap(_.hcursor.get[String]("access_token"))
          .getOrElse(fail(s"Sin access_token: $body"))
        alerts <- app.run(
          TestTokens.withBearer(Request[IO](Method.GET, uri"/api/v1/alerts"))(token)
        )
        evaluation <- app.run(
          TestTokens.withBearer(Request[IO](Method.GET, uri"/api/v1/alerts/evaluation"))(token)
        )
        rates <- app.run(
          TestTokens.withBearer(Request[IO](Method.GET, uri"/api/v1/rates/current"))(token)
        )
      yield
        assertEquals(alerts.status, Status.Ok)
        assertEquals(evaluation.status, Status.Ok)
        assertEquals(rates.status, Status.Ok)
    }

  test("una ruta inexistente responde 404 Problem Details sin exigir token"):
    app.flatMap { app =>
      app.run(Request[IO](Method.GET, uri"/no-existe")).map { response =>
        assertEquals(response.status, Status.NotFound)
      }
    }
