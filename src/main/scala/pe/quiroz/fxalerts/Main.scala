package pe.quiroz.fxalerts

import cats.effect.{IO, IOApp, Resource}
import org.http4s.server.Server
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import pe.quiroz.fxalerts.application.health.HealthService
import pe.quiroz.fxalerts.infrastructure.config.{AppConfig, ConfigLoader}
import pe.quiroz.fxalerts.infrastructure.http.health.HealthRoutes
import pe.quiroz.fxalerts.infrastructure.http.{HttpApi, HttpServer}
import pe.quiroz.fxalerts.infrastructure.persistence.{
  Database,
  DoobieDatabaseHealthCheck,
  FlywayMigrator
}

import scala.concurrent.duration.*

object Main extends IOApp.Simple:

  private given Logger[IO] = Slf4jLogger.getLogger[IO]

  private val databaseHealthTimeout: FiniteDuration = 2.seconds

  def run: IO[Unit] =
    ConfigLoader.load[IO].flatMap(config => application(config).useForever)

  private def application(config: AppConfig): Resource[IO, Server] =
    for
      transactor <- Database.transactor[IO](config.database)
      _          <- Resource.eval(FlywayMigrator.migrate[IO](transactor, config.database.schema))
      healthService = HealthService[IO](
        DoobieDatabaseHealthCheck[IO](transactor),
        databaseHealthTimeout
      )
      httpApp = HttpApi.httpApp[IO](HealthRoutes[IO](healthService))
      server <- HttpServer.resource[IO](config.http, httpApp)
      _      <- Resource.eval(
        Logger[IO].info(
          s"Servidor HTTP escuchando en http://${config.http.host}:${config.http.port} " +
            "(documentación en /docs)"
        )
      )
    yield server
