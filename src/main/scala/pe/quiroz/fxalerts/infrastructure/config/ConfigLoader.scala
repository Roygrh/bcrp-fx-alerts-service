package pe.quiroz.fxalerts.infrastructure.config

import cats.effect.Async
import cats.syntax.all.*
import ciris.*
import ciris.http4s.*
import com.comcast.ip4s.{host, port, Host, Port}

/**
 * Carga la configuración exclusivamente desde variables de entorno.
 *
 * Las variables sin valor por defecto son obligatorias; si falta alguna, ciris reporta todas las
 * ausentes en un único error al arrancar. `DB_PASSWORD` se envuelve en [[ciris.Secret]] para que
 * nunca aparezca en mensajes de error ni en `toString`.
 */
object ConfigLoader:

  private val http: ConfigValue[Effect, HttpConfig] =
    (
      env("HTTP_HOST").as[Host].default(host"0.0.0.0"),
      env("HTTP_PORT").as[Port].default(port"8080")
    ).parMapN(HttpConfig.apply)

  private val database: ConfigValue[Effect, DatabaseConfig] =
    (
      env("DB_HOST").default("localhost"),
      env("DB_PORT").as[Int].default(5432),
      env("DB_NAME"),
      env("DB_SCHEMA").default("fx_alerts"),
      env("DB_USER"),
      env("DB_PASSWORD").secret,
      env("DB_POOL_SIZE").as[Int].default(10)
    ).parMapN(DatabaseConfig.apply)

  val config: ConfigValue[Effect, AppConfig] =
    (http, database).parMapN(AppConfig.apply)

  def load[F[_]: Async]: F[AppConfig] = config.load[F]
