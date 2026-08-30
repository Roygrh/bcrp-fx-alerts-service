package pe.quiroz.fxalerts.infrastructure.http.alert

import io.circe.Codec
import pe.quiroz.fxalerts.application.alert.{CreateAlert, UpdateAlert}
import pe.quiroz.fxalerts.domain.alert.{AlertStatus, BcrpSeries, CrossingDirection}
import sttp.tapir.Schema

import AlertJson.given

/**
 * Cuerpo de `POST /api/v1/alerts`.
 *
 * Modelo propio de la frontera HTTP: transporta los datos tal como los envía el consumidor
 * (`clientId` y `threshold` sin validar) y se traduce al comando de aplicación, de modo que el
 * contrato público y el dominio pueden evolucionar por separado. Las invariantes de negocio las
 * valida el dominio; aquí solo se exige que el JSON tenga la forma esperada.
 *
 * @param clientId
 *   identificador del cliente comercial propietario
 * @param series
 *   código de la serie del BCRP a observar
 * @param threshold
 *   umbral de tipo de cambio (positivo, hasta cuatro decimales)
 * @param direction
 *   sentido del cruce que dispara la alerta
 */
final case class CreateAlertRequest(
    clientId: String,
    series: BcrpSeries,
    threshold: BigDecimal,
    direction: CrossingDirection
) derives Codec.AsObject,
      Schema:

  def toCommand: CreateAlert =
    CreateAlert(clientId = clientId, series = series, threshold = threshold, direction = direction)

object CreateAlertRequest:

  val example: CreateAlertRequest = CreateAlertRequest(
    clientId = "cliente-001",
    series = BcrpSeries.UsdPenSbsSell,
    threshold = BigDecimal("3.85"),
    direction = CrossingDirection.Above
  )

/**
 * Cuerpo de `PUT /api/v1/alerts/{id}`.
 *
 * Reemplazo completo de la configuración (semántica de `PUT`): se envían todos los campos
 * modificables, incluido `status`. El identificador, el cliente propietario y la fecha de creación
 * son inmutables y por eso no forman parte del cuerpo.
 *
 * @param series
 *   código de la serie del BCRP a observar
 * @param threshold
 *   umbral de tipo de cambio (positivo, hasta cuatro decimales)
 * @param direction
 *   sentido del cruce que dispara la alerta
 * @param status
 *   estado final de la alerta
 */
final case class UpdateAlertRequest(
    series: BcrpSeries,
    threshold: BigDecimal,
    direction: CrossingDirection,
    status: AlertStatus
) derives Codec.AsObject,
      Schema:

  def toCommand: UpdateAlert =
    UpdateAlert(series = series, threshold = threshold, direction = direction, status = status)

object UpdateAlertRequest:

  val example: UpdateAlertRequest = UpdateAlertRequest(
    series = BcrpSeries.UsdPenSbsSell,
    threshold = BigDecimal("3.95"),
    direction = CrossingDirection.Below,
    status = AlertStatus.Inactive
  )
