package pe.quiroz.fxalerts.infrastructure.http

import cats.effect.{Async, Resource}
import fs2.io.net.Network
import org.http4s.HttpApp
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Server
import pe.quiroz.fxalerts.infrastructure.config.HttpConfig

/** Servidor HTTP basado en Ember, gestionado como `Resource` para un apagado ordenado. */
object HttpServer:

  def resource[F[_]: Async: Network](config: HttpConfig, app: HttpApp[F]): Resource[F, Server] =
    EmberServerBuilder
      .default[F]
      .withHost(config.host)
      .withPort(config.port)
      .withHttpApp(app)
      .build
