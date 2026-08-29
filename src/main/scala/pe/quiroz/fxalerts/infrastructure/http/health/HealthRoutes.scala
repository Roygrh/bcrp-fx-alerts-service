package pe.quiroz.fxalerts.infrastructure.http.health

import cats.Functor
import cats.syntax.all.*
import pe.quiroz.fxalerts.application.health.{ComponentStatus, HealthService}
import sttp.tapir.server.ServerEndpoint

/** Enlaza el endpoint de salud con su lógica de servidor. */
final class HealthRoutes[F[_]: Functor](service: HealthService[F]):

  val serverEndpoints: List[ServerEndpoint[Any, F]] = List(
    HealthEndpoints.health.serverLogic { _ =>
      service.check.map { report =>
        val body = HealthResponse.from(report)
        Either.cond(report.status == ComponentStatus.Up, body, body)
      }
    }
  )
