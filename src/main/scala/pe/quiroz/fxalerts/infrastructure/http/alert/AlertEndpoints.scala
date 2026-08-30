package pe.quiroz.fxalerts.infrastructure.http.alert

import pe.quiroz.fxalerts.domain.alert.AlertId
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
 * salida de error común ([[ApiError.output]]) documenta 400, 404 y 500 en formato Problem Details.
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

  val create: PublicEndpoint[CreateAlertRequest, ApiError, (String, AlertResponse), Any] =
    base.post
      .summary("Registra una alerta")
      .description(
        "Crea una alerta de tipo de cambio para el cliente indicado. Toda alerta nace activa. " +
          "Responde 201 con la representación creada y su ruta en la cabecera `Location`."
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

  val list: PublicEndpoint[Option[String], ApiError, AlertListResponse, Any] =
    base.get
      .summary("Lista alertas")
      .description(
        "Devuelve las alertas ordenadas por fecha de creación ascendente. " +
          "El parámetro `clientId` restringe el resultado a las alertas de ese cliente."
      )
      .in(
        query[Option[String]]("clientId")
          .description("Si se indica, filtra por cliente propietario")
          .example(Some("cliente-001"))
      )
      .out(jsonBody[AlertListResponse].example(AlertListResponse.example))

  val getById: PublicEndpoint[UUID, ApiError, AlertResponse, Any] =
    base.get
      .in(alertIdPath)
      .summary("Obtiene una alerta")
      .description("Devuelve la alerta identificada por `id`, o 404 si no existe.")
      .out(jsonBody[AlertResponse].example(AlertResponse.example))

  val update: PublicEndpoint[(UUID, UpdateAlertRequest), ApiError, AlertResponse, Any] =
    base.put
      .in(alertIdPath)
      .summary("Reemplaza una alerta")
      .description(
        "Reemplazo completo de la configuración (semántica de `PUT`). El identificador, el " +
          "cliente propietario y la fecha de creación no cambian; `updatedAt` se actualiza."
      )
      .in(jsonBody[UpdateAlertRequest].example(UpdateAlertRequest.example))
      .out(jsonBody[AlertResponse].example(AlertResponse.exampleUpdated))

  val delete: PublicEndpoint[UUID, ApiError, Unit, Any] =
    base.delete
      .in(alertIdPath)
      .summary("Elimina una alerta")
      .description("Elimina la alerta identificada por `id`, o responde 404 si no existe.")
      .out(statusCode(StatusCode.NoContent).description("Alerta eliminada"))
