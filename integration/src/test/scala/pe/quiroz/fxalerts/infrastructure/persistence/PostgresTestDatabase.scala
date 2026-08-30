package pe.quiroz.fxalerts.infrastructure.persistence

import cats.effect.{IO, Resource}
import ciris.Secret
import doobie.hikari.HikariTransactor
import org.testcontainers.postgresql.PostgreSQLContainer
import pe.quiroz.fxalerts.infrastructure.config.DatabaseConfig

/**
 * PostgreSQL efímero para pruebas de integración.
 *
 * Levanta un contenedor con la misma imagen que `docker-compose.yml`, construye el transactor con
 * el mismo código que usa la aplicación ([[Database]]) y aplica las migraciones reales
 * ([[FlywayMigrator]]): así las pruebas verifican también que las migraciones son válidas. El
 * contenedor se destruye al liberar el `Resource`; cada ejecución parte de una base de datos limpia
 * y no depende de puertos ni credenciales locales.
 */
object PostgresTestDatabase:

  private val image: String  = "postgres:16-alpine"
  private val schema: String = "fx_alerts"

  val transactor: Resource[IO, HikariTransactor[IO]] =
    container
      .flatMap(container => Database.transactor[IO](config(container)))
      .evalTap(FlywayMigrator.migrate[IO](_, schema))

  private def container: Resource[IO, PostgreSQLContainer] =
    Resource.make(IO.blocking {
      val container = new PostgreSQLContainer(image)
      container.start()
      container
    })(container => IO.blocking(container.stop()))

  private def config(container: PostgreSQLContainer): DatabaseConfig =
    DatabaseConfig(
      host = container.getHost,
      port = container.getFirstMappedPort,
      name = container.getDatabaseName,
      schema = schema,
      user = container.getUsername,
      password = Secret(container.getPassword),
      poolSize = 2
    )
