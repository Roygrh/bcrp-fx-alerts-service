package pe.quiroz.fxalerts.domain.alert

import pe.quiroz.fxalerts.domain.DomainError
import pe.quiroz.fxalerts.domain.rate.ExchangeRate

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

  /**
   * Evalúa la alerta contra un valor de tipo de cambio.
   *
   * Es la regla de negocio central del servicio y es pura: no depende de relojes, repositorios ni
   * de cómo se obtuvo el dato. Se aplica en este orden:
   *
   *   1. una alerta inactiva no se evalúa nunca ([[AlertOutcome.Inactive]]): es una propiedad de la
   *      propia alerta, previa a cualquier dato;
   *   2. un dato de otra serie no se evalúa ([[AlertOutcome.SeriesMismatch]]): el umbral solo tiene
   *      sentido frente a la serie que la alerta observa;
   *   3. en otro caso se aplica la regla de cruce de [[CrossingDirection.crossed]], estricta en
   *      ambos sentidos: un valor exactamente igual al umbral no dispara.
   *
   * La procedencia del dato (oficial o no) no altera el resultado: la regla decide si el valor
   * cruzó el umbral, y la calidad de ese valor se expresa aparte ([[EvaluationBasis]]).
   */
  def evaluate(rate: ExchangeRate): AlertOutcome =
    if status == AlertStatus.Inactive then AlertOutcome.Inactive
    else if rate.series != series then AlertOutcome.SeriesMismatch
    else if direction.crossed(rate.value, threshold.value) then AlertOutcome.Triggered
    else AlertOutcome.NotTriggered

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
   *   cliente propietario, ya validado: es la identidad autenticada de quien registra la alerta, no
   *   un dato que el consumidor pueda elegir
   * @param threshold
   *   umbral sin validar
   * @param createdAt
   *   instante de creación; se usa también como `updatedAt` inicial
   */
  def create(
      id: AlertId,
      clientId: ClientId,
      series: BcrpSeries,
      threshold: BigDecimal,
      direction: CrossingDirection,
      createdAt: Instant
  ): Either[DomainError, Alert] =
    Threshold.from(threshold).map { validThreshold =>
      Alert(
        id = id,
        clientId = clientId,
        series = series,
        threshold = validThreshold,
        direction = direction,
        status = AlertStatus.Active,
        createdAt = createdAt,
        updatedAt = createdAt
      )
    }
