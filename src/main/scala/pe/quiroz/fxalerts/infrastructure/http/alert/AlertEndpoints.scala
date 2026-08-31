package pe.quiroz.fxalerts.infrastructure.http.alert

import pe.quiroz.fxalerts.application.security.Scope
import pe.quiroz.fxalerts.domain.alert.AlertId
import pe.quiroz.fxalerts.infrastructure.http.auth.SecuredEndpoint
import pe.quiroz.fxalerts.infrastructure.http.problem.ApiError
import sttp.model.{HeaderNames, StatusCode}
import sttp.tapir.*
import sttp.tapir.json.circe.*

import java.util.UUID

/**
 * Definición declarativa del CRUD de alertas bajo `/api/v1/alerts`.
 *
 * La versión viaja en la ruta (`v1`) y no en una cabecera o parámetro: es visible en logs, proxies
 * y en el propio Swagger UI, y permite convivir dos versiones montadas en prefijos distintos si
 * algún día hace falta una ruptura del contrato.
 *
 * Cada endpoint es un valor puro del que se derivan las rutas http4s y la documentación OpenAPI; la
 * salida de error común ([[ApiError.output]]) documenta 400, 401, 403, 404, 503 y 500 en formato
 * Problem Details.
 *
 * Todos los endpoints exigen un token: las lecturas el alcance `alerts:read` y las escrituras
 * `alerts:write`. El recurso está acotado al cliente autenticado: no existe forma de nombrar a otro
 * cliente, ni al crear (el propietario es el sujeto del token) ni al listar (solo las propias), y
 * una alerta ajena responde 404 exactamente igual que una inexistente.
 *
 * `evaluation` es un subrecurso de solo lectura de la colección ("mis alertas frente al tipo de
 * cambio vigente"): se lee con `alerts:read`, el mismo alcance que la propia colección, porque no
 * revela nada sobre las alertas que ese alcance no revele ya, y el tipo de cambio que incorpora es
 * el contexto necesario para interpretar el resultado.
 */
object AlertEndpoints:

  /** Prefijo público del recurso; debe mantenerse alineado con la ruta declarada en `base`. */
  val basePath: String = "/api/v1/alerts"

  /** Ruta pública de una alerta concreta; origen de la cabecera `Location` del alta. */
  def alertLocation(id: AlertId): String = s"$basePath/${id.value}"

  private val exampleId = UUID.fromString("6f1c2a3e-9d4b-4c1a-8e2f-0b7d5a6c4e21")

  private val alertIdPath: EndpointInput.PathCapture[UUID] =
    path[UUID]("id")
      .description("Identificador de la alerta")
      .example(exampleId)

  private val base =
    endpoint
      .in("api" / "v1" / "alerts")
      .tag("Alertas")
      .errorOut(ApiError.output)

  val create: SecuredEndpoint[CreateAlertRequest, (String, AlertResponse)] =
    SecuredEndpoint(Scope.AlertsWrite)(
      base.post
        .summary("Registra una alerta")
        .description(
          "Crea una alerta de tipo de cambio para el cliente autenticado (el propietario es el " +
            "sujeto del token). Toda alerta nace activa. Responde 201 con la representación " +
            "creada y su ruta en la cabecera `Location`."
        )
        .in(
          jsonBody[CreateAlertRequest]
            .description("Configuración de la alerta")
            .example(CreateAlertRequest.example)
        )
        .out(statusCode(StatusCode.Created).description("Alerta creada"))
        .out(
          header[String](HeaderNames.Location)
            .description("Ruta de la alerta creada")
            .example(s"$basePath/$exampleId")
        )
        .out(jsonBody[AlertResponse].example(AlertResponse.example))
    )

  val list: SecuredEndpoint[Unit, AlertListResponse] =
    SecuredEndpoint(Scope.AlertsRead)(
      base.get
        .summary("Lista las alertas propias")
        .description(
          "Devuelve las alertas del cliente autenticado ordenadas por fecha de creación " +
            "ascendente y, a igualdad de fecha, por identificador. Ese desempate es estable pero " +
            "no preserva el orden real de creación entre alertas registradas en el mismo instante."
        )
        .out(jsonBody[AlertListResponse].example(AlertListResponse.example))
    )

  val evaluate: SecuredEndpoint[Unit, AlertEvaluationResponse] =
    SecuredEndpoint(Scope.AlertsRead)(
      base.get
        .in("evaluation")
        .summary("Evalúa las alertas propias contra el tipo de cambio vigente")
        .description(
          "Cruza el último tipo de cambio disponible de la serie de referencia con todas las " +
            "alertas del cliente autenticado y devuelve el resultado de cada una. `ABOVE` se " +
            "dispara cuando el valor es estrictamente mayor que el umbral y `BELOW` cuando es " +
            "estrictamente menor: un valor igual al umbral no dispara en ningún sentido. Una " +
            "alerta `INACTIVE` o de otra serie no se evalúa y lo indica en `outcome`. La " +
            "evaluación no se persiste ni genera notificaciones; se recalcula en cada llamada.\n\n" +
            "`rate` es el dato usado, con la misma representación que `GET /api/v1/rates/current` " +
            "(procedencia en `source`, frescura en `freshness`). `basis` y `conclusive` resumen " +
            "su calidad como fundamento para actuar: solo `OFFICIAL_CONFIRMED` (precio oficial " +
            "confirmado por su fuente dentro del periodo de validez) es concluyente; " +
            "`MARKET_REFERENCE` (respaldo no oficial) y `UNCONFIRMED` (último valor conocido, " +
            "ninguna fuente responde) no lo son. El servicio no aplica ninguna política sobre los " +
            "casos no concluyentes: expone la información y deja la decisión al consumidor.\n\n" +
            "Se devuelven todas las alertas con su resultado, no solo las disparadas; un cliente " +
            "sin alertas recibe 200 con `items` vacío. Responde 404 si no hay dato publicado en " +
            "la ventana consultada y 503 si ninguna fuente responde y no hay dato en caché: sin " +
            "tipo de cambio no hay evaluación posible."
        )
        .out(
          jsonBody[AlertEvaluationResponse].examples(
            List(
              EndpointIO.Example.of(
                AlertEvaluationResponse.example,
                name = Some("Precio oficial confirmado (concluyente)")
              ),
              EndpointIO.Example.of(
                AlertEvaluationResponse.exampleMarketReference,
                name = Some("Referencia de mercado no oficial (no concluyente)")
              ),
              EndpointIO.Example.of(
                AlertEvaluationResponse.exampleUnconfirmed,
                name = Some("Dato sin confirmar, ninguna fuente responde (no concluyente)")
              ),
              EndpointIO.Example.of(
                AlertEvaluationResponse.exampleEmpty,
                name = Some("Cliente sin alertas")
              )
            )
          )
        )
    )

  val getById: SecuredEndpoint[UUID, AlertResponse] =
    SecuredEndpoint(Scope.AlertsRead)(
      base.get
        .in(alertIdPath)
        .summary("Obtiene una alerta")
        .description(
          "Devuelve la alerta identificada por `id`, o 404 si no existe o no pertenece al cliente " +
            "autenticado."
        )
        .out(jsonBody[AlertResponse].example(AlertResponse.example))
    )

  val update: SecuredEndpoint[(UUID, UpdateAlertRequest), AlertResponse] =
    SecuredEndpoint(Scope.AlertsWrite)(
      base.put
        .in(alertIdPath)
        .summary("Reemplaza una alerta")
        .description(
          "Reemplazo completo de la configuración (semántica de `PUT`). El identificador, el " +
            "cliente propietario y la fecha de creación no cambian; `updatedAt` se actualiza. " +
            "Responde 404 si la alerta no existe o no pertenece al cliente autenticado."
        )
        .in(jsonBody[UpdateAlertRequest].example(UpdateAlertRequest.example))
        .out(jsonBody[AlertResponse].example(AlertResponse.exampleUpdated))
    )

  val delete: SecuredEndpoint[UUID, Unit] =
    SecuredEndpoint(Scope.AlertsWrite)(
      base.delete
        .in(alertIdPath)
        .summary("Elimina una alerta")
        .description(
          "Elimina la alerta identificada por `id`, o responde 404 si no existe o no pertenece al " +
            "cliente autenticado."
        )
        .out(statusCode(StatusCode.NoContent).description("Alerta eliminada"))
    )
