package pe.quiroz.fxalerts.infrastructure.remote

import cats.effect.{Async, Resource}
import fs2.io.net.Network
import org.http4s.ProductId
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.headers.`User-Agent`
import pe.quiroz.fxalerts.infrastructure.config.RemoteCallConfig

/**
 * Cliente HTTP (Ember) para hablar con servicios externos.
 *
 * Ember aplica `timeout` como tiempo máximo de espera por las cabeceras de respuesta; no expone un
 * tiempo de conexión independiente, por lo que este se hace efectivo como parte del presupuesto por
 * intento ([[RemoteCallConfig.attemptTimeout]]) que impone [[RemoteCall]]. Cada fuente externa
 * recibe su propio cliente porque sus tiempos de lectura pueden diferir. El `User-Agent` identifica
 * al servicio ante los proveedores.
 */
object RemoteHttpClient:

  val userAgent: `User-Agent` = `User-Agent`(ProductId("bcrp-fx-alerts-service", Some("0.1.0")))

  def resource[F[_]: Async: Network](config: RemoteCallConfig): Resource[F, Client[F]] =
    EmberClientBuilder
      .default[F]
      .withTimeout(config.readTimeout)
      .withUserAgent(userAgent)
      .build
