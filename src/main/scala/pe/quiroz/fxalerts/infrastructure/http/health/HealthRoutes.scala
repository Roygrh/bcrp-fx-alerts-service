package pe.quiroz.fxalerts.infrastructure.http.health

import cats.Functor
import cats.syntax.all.*
import pe.quiroz.fxalerts.application.health.{ComponentStatus, HealthService}
import sttp.tapir.server.ServerEndpoint

/**
 * Enlaza el endpoint de salud con su lógica de servidor.
 *
 * `UP` y `DEGRADED` responden 200: la instancia sigue siendo capaz de atender y un orquestador no
 * debe retirarla ni reiniciarla. Solo `DOWN` responde 503.
 */
final class HealthRoutes[F[_]: Functor](service: HealthService[F]):

  val serverEndpoints: List[ServerEndpoint[Any, F]] = List(
    HealthEndpoints.health.serverLogic { _ =>
      service.check.map { report =>
        val body = HealthResponse.from(report)
        Either.cond(report.status != ComponentStatus.Down, body, body)
      }
    }
  )
