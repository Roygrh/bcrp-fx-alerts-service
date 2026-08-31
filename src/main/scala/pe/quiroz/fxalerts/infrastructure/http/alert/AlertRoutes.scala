package pe.quiroz.fxalerts.infrastructure.http.alert

import cats.Monad
import cats.syntax.all.*
import pe.quiroz.fxalerts.application.alert.{AlertEvaluationService, AlertService}
import pe.quiroz.fxalerts.domain.alert.AlertId
import pe.quiroz.fxalerts.infrastructure.http.auth.BearerAuthentication
import pe.quiroz.fxalerts.infrastructure.http.problem.ApiError
import sttp.tapir.server.ServerEndpoint

/**
 * Enlaza los endpoints del recurso de alertas (CRUD y evaluación) con los servicios de aplicación.
 *
 * La lógica de esta capa se limita a traducir: modelos de frontera a comandos, entidades a
 * respuestas y errores de dominio a errores HTTP mediante [[ApiError.fromDomain]]. La identidad
 * autenticada que entrega la lógica de seguridad se pasa al servicio como cliente propietario de
 * cada operación: ningún dato de la petición puede sustituirla.
 */
final class AlertRoutes[F[_]: Monad](
    service: AlertService[F],
    evaluation: AlertEvaluationService[F],
    auth: BearerAuthentication[F]
):

  /**
   * El orden importa: `GET /api/v1/alerts/evaluation` y `GET /api/v1/alerts/{id}` tienen la misma
   * forma y Tapir prueba los endpoints en este orden. Si `getById` fuera antes, "evaluation"
   * fallaría al decodificarse como UUID y se respondería 400 sin llegar al endpoint de evaluación.
   */
  val serverEndpoints: List[ServerEndpoint[Any, F]] = List(
    AlertEndpoints.create.serverLogic(auth) { client => request =>
      service
        .create(client.clientId, request.toCommand)
        .map(
          _.bimap(
            ApiError.fromDomain,
            alert => (AlertEndpoints.alertLocation(alert.id), AlertResponse.from(alert))
          )
        )
    },
    AlertEndpoints.list.serverLogic(auth) { client => _ =>
      service
        .list(client.clientId)
        .map(alerts => AlertListResponse(alerts.map(AlertResponse.from)).asRight[ApiError])
    },
    AlertEndpoints.evaluate.serverLogic(auth) { client => _ =>
      evaluation
        .evaluate(client.clientId)
        .map(_.bimap(ApiError.fromDomain, AlertEvaluationResponse.from))
    },
    AlertEndpoints.getById.serverLogic(auth) { client => id =>
      service
        .get(client.clientId, AlertId(id))
        .map(_.bimap(ApiError.fromDomain, AlertResponse.from))
    },
    AlertEndpoints.update.serverLogic(auth) { client => (id, request) =>
      service
        .update(client.clientId, AlertId(id), request.toCommand)
        .map(_.bimap(ApiError.fromDomain, AlertResponse.from))
    },
    AlertEndpoints.delete.serverLogic(auth) { client => id =>
      service.delete(client.clientId, AlertId(id)).map(_.leftMap(ApiError.fromDomain))
    }
  )
