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
import pe.quiroz.fxalerts.infrastructure.http.HttpApi

import scala.concurrent.duration.*

class HealthRoutesSuite extends CatsEffectSuite:

  private given Logger[IO] = Slf4jLogger.getLogger[IO]

  private def httpApp(databasePing: IO[Unit]): HttpApp[IO] =
    val databaseCheck = new DatabaseHealthCheck[IO]:
      def ping: IO[Unit] = databasePing
    HttpApi.fromEndpoints[IO](
      HealthRoutes[IO](HealthService[IO](databaseCheck, 1.second)).serverEndpoints
    )

  test("GET /health responde 200 con status UP cuando la base de datos responde"):
    val request = Request[IO](Method.GET, uri"/health")
    for
      response <- httpApp(IO.unit).run(request)
      body     <- response.bodyText.compile.string
    yield
      assertEquals(response.status, Status.Ok)
      assertEquals(
        parse(body),
        Right(Json.obj("status" -> "UP".asJson, "database" -> Json.obj("status" -> "UP".asJson)))
      )
