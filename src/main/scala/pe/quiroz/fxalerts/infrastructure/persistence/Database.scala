package pe.quiroz.fxalerts.infrastructure.persistence

import cats.effect.{Async, Resource, Sync}
import com.zaxxer.hikari.HikariConfig
import doobie.hikari.HikariTransactor
import pe.quiroz.fxalerts.infrastructure.config.DatabaseConfig

/** Construye el transactor de doobie respaldado por un pool de conexiones HikariCP. */
object Database:

  def transactor[F[_]: Async](config: DatabaseConfig): Resource[F, HikariTransactor[F]] =
    Resource
      .eval(Sync[F].delay(hikariConfig(config)))
      .flatMap(HikariTransactor.fromHikariConfig[F](_))

  private def hikariConfig(config: DatabaseConfig): HikariConfig =
    val hikari = new HikariConfig()
    hikari.setJdbcUrl(config.jdbcUrl)
    hikari.setUsername(config.user)
    hikari.setPassword(config.password.value)
    hikari.setSchema(config.schema)
    hikari.setMaximumPoolSize(config.poolSize)
    hikari.setPoolName("bcrp-fx-alerts-pool")
    hikari
