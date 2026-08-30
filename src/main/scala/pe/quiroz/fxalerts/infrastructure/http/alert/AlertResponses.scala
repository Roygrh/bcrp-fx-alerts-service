package pe.quiroz.fxalerts.infrastructure.http.alert

import io.circe.Codec
import pe.quiroz.fxalerts.domain.alert.{Alert, AlertStatus, BcrpSeries, CrossingDirection}
import sttp.tapir.Schema

import java.time.Instant
import java.util.UUID

import AlertJson.given

/**
 * Representación pública de una alerta.
 *
 * Es un modelo de la frontera HTTP, no la entidad de dominio: los tipos con invariantes se
 * proyectan a primitivos (`UUID`, `String`, `BigDecimal`) y la forma del JSON puede evolucionar sin
 * arrastrar cambios del dominio, y al revés.
 */
final case class AlertResponse(
    id: UUID,
    clientId: String,
    series: BcrpSeries,
    threshold: BigDecimal,
    direction: CrossingDirection,
    status: AlertStatus,
    createdAt: Instant,
    updatedAt: Instant
) derives Codec.AsObject,
      Schema

object AlertResponse:

  def from(alert: Alert): AlertResponse =
    AlertResponse(
      id = alert.id.value,
      clientId = alert.clientId.value,
      series = alert.series,
      threshold = alert.threshold.value,
      direction = alert.direction,
      status = alert.status,
      createdAt = alert.createdAt,
      updatedAt = alert.updatedAt
    )

  val example: AlertResponse = AlertResponse(
    id = UUID.fromString("6f1c2a3e-9d4b-4c1a-8e2f-0b7d5a6c4e21"),
    clientId = "cliente-001",
    series = BcrpSeries.UsdPenSbsSell,
    threshold = BigDecimal("3.85"),
    direction = CrossingDirection.Above,
    status = AlertStatus.Active,
    createdAt = Instant.parse("2026-08-30T15:30:00Z"),
    updatedAt = Instant.parse("2026-08-30T15:30:00Z")
  )

  /** Ejemplo tras un reemplazo con `PUT`: mismo identificador y cliente, configuración nueva. */
  val exampleUpdated: AlertResponse = example.copy(
    threshold = BigDecimal("3.95"),
    direction = CrossingDirection.Below,
    status = AlertStatus.Inactive,
    updatedAt = Instant.parse("2026-08-30T16:00:00Z")
  )

/**
 * Envoltorio del listado de alertas.
 *
 * Se devuelve un objeto y no un arreglo JSON al nivel raíz para poder incorporar metadatos (por
 * ejemplo, paginación) sin romper el contrato.
 */
final case class AlertListResponse(items: List[AlertResponse]) derives Codec.AsObject, Schema

object AlertListResponse:

  val example: AlertListResponse = AlertListResponse(List(AlertResponse.example))
