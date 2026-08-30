package pe.quiroz.fxalerts.domain.alert

import pe.quiroz.fxalerts.domain.DomainError

import java.time.Instant

/**
 * Alerta de tipo de cambio registrada por un cliente comercial.
 *
 * Los campos con invariantes propias (`clientId`, `threshold`) son tipos que solo pueden
 * construirse a través de su constructor inteligente, por lo que una instancia de `Alert` es válida
 * por construcción. Las fábricas [[Alert.create]] y [[Alert.update]] son el único punto de entrada
 * para datos sin validar.
 *
 * @param id
 *   identificador único
 * @param clientId
 *   cliente comercial propietario
 * @param series
 *   serie del BCRP observada
 * @param threshold
 *   umbral de tipo de cambio
 * @param direction
 *   sentido del cruce que dispara la alerta
 * @param status
 *   activa o inactiva
 * @param createdAt
 *   instante de creación
 * @param updatedAt
 *   instante de la última modificación (igual a `createdAt` mientras no haya cambios)
 */
final case class Alert(
    id: AlertId,
    clientId: ClientId,
    series: BcrpSeries,
    threshold: Threshold,
    direction: CrossingDirection,
    status: AlertStatus,
    createdAt: Instant,
    updatedAt: Instant
):

  /**
   * Devuelve una copia con la configuración reemplazada y `updatedAt` actualizado.
   *
   * El identificador, el cliente propietario y `createdAt` son inmutables. El umbral se valida con
   * las mismas reglas que en la creación.
   */
  def update(
      series: BcrpSeries,
      threshold: BigDecimal,
      direction: CrossingDirection,
      status: AlertStatus,
      updatedAt: Instant
  ): Either[DomainError, Alert] =
    Threshold.from(threshold).map { validThreshold =>
      copy(
        series = series,
        threshold = validThreshold,
        direction = direction,
        status = status,
        updatedAt = updatedAt
      )
    }

object Alert:

  /**
   * Constructor inteligente: valida los datos de entrada y devuelve la alerta o el primer error de
   * dominio encontrado.
   *
   * Toda alerta nace activa: registrarla expresa la intención de ser avisado, y desactivarla es una
   * acción posterior explícita del cliente.
   *
   * @param id
   *   identificador ya generado por la capa de aplicación
   * @param clientId
   *   identificador del cliente tal como lo envía el consumidor (sin validar)
   * @param threshold
   *   umbral sin validar
   * @param createdAt
   *   instante de creación; se usa también como `updatedAt` inicial
   */
  def create(
      id: AlertId,
      clientId: String,
      series: BcrpSeries,
      threshold: BigDecimal,
      direction: CrossingDirection,
      createdAt: Instant
  ): Either[DomainError, Alert] =
    for
      validClientId  <- ClientId.from(clientId)
      validThreshold <- Threshold.from(threshold)
    yield Alert(
      id = id,
      clientId = validClientId,
      series = series,
      threshold = validThreshold,
      direction = direction,
      status = AlertStatus.Active,
      createdAt = createdAt,
      updatedAt = createdAt
    )
