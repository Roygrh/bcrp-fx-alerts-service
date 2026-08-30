package pe.quiroz.fxalerts.infrastructure.http.alert

import cats.Monad
import cats.syntax.all.*
import pe.quiroz.fxalerts.application.alert.AlertService
import pe.quiroz.fxalerts.domain.alert.{AlertId, ClientId}
import pe.quiroz.fxalerts.infrastructure.http.problem.ApiError
import sttp.tapir.server.ServerEndpoint

/**
 * Enlaza los endpoints del CRUD de alertas con el servicio de aplicación.
 *
 * La lógica de esta capa se limita a traducir: modelos de frontera a comandos, entidades a
 * respuestas y errores de dominio a errores HTTP mediante [[ApiError.fromDomain]]. El filtro
 * `clientId` del listado se valida con el mismo constructor inteligente del dominio, de modo que un
 * filtro que jamás podría coincidir (vacío o demasiado largo) se rechaza con 400 en lugar de
 * devolver una lista vacía engañosa.
 */
final class AlertRoutes[F[_]: Monad](service: AlertService[F]):

  val serverEndpoints: List[ServerEndpoint[Any, F]] = List(
    AlertEndpoints.create.serverLogic { request =>
      service
        .create(request.toCommand)
        .map(
          _.bimap(
            ApiError.fromDomain,
            alert => (AlertEndpoints.alertLocation(alert.id), AlertResponse.from(alert))
          )
        )
    },
    AlertEndpoints.list.serverLogic { clientId =>
      clientId.traverse(ClientId.from) match
        case Left(error) =>
          ApiError.fromDomain(error).asLeft[AlertListResponse].pure[F]
        case Right(filter) =>
          service
            .list(filter)
            .map(alerts => AlertListResponse(alerts.map(AlertResponse.from)).asRight[ApiError])
    },
    AlertEndpoints.getById.serverLogic { id =>
      service.get(AlertId(id)).map(_.bimap(ApiError.fromDomain, AlertResponse.from))
    },
    AlertEndpoints.update.serverLogic { case (id, request) =>
      service
        .update(AlertId(id), request.toCommand)
        .map(_.bimap(ApiError.fromDomain, AlertResponse.from))
    },
    AlertEndpoints.delete.serverLogic { id =>
      service.delete(AlertId(id)).map(_.leftMap(ApiError.fromDomain))
    }
  )
