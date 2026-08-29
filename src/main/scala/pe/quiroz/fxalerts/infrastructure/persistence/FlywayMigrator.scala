package pe.quiroz.fxalerts.infrastructure.persistence

import cats.effect.Sync
import cats.syntax.all.*
import doobie.hikari.HikariTransactor
import org.flywaydb.core.Flyway

/**
 * Ejecuta las migraciones de Flyway reutilizando el `DataSource` del transactor.
 *
 * El historial de migraciones y las tablas de negocio se ubican en el esquema configurado, de modo
 * que el servicio no escribe nada en `public`.
 */
object FlywayMigrator:

  def migrate[F[_]: Sync](transactor: HikariTransactor[F], schema: String): F[Unit] =
    transactor.configure { dataSource =>
      Sync[F].blocking {
        Flyway
          .configure()
          .dataSource(dataSource)
          .schemas(schema)
          .locations("classpath:db/migration")
          .load()
          .migrate()
      }.void
    }
