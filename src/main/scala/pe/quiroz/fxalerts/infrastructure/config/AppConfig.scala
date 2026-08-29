package pe.quiroz.fxalerts.infrastructure.config

import ciris.Secret
import com.comcast.ip4s.{Host, Port}

final case class HttpConfig(host: Host, port: Port)

final case class DatabaseConfig(
    host: String,
    port: Int,
    name: String,
    schema: String,
    user: String,
    password: Secret[String],
    poolSize: Int
):
  def jdbcUrl: String = s"jdbc:postgresql://$host:$port/$name"

final case class AppConfig(http: HttpConfig, database: DatabaseConfig)
