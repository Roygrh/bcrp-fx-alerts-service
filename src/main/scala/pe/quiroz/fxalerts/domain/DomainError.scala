package pe.quiroz.fxalerts.domain

import pe.quiroz.fxalerts.domain.alert.{AlertId, ClientIdViolation, ThresholdViolation}

/**
 * Raíz de la jerarquía de errores de dominio.
 *
 * Se mantiene sellada para que el compilador exija exhaustividad al mapear errores de dominio a
 * respuestas HTTP. Cada variante lleva los datos necesarios para construir un mensaje útil sin
 * depender de texto libre.
 */
sealed trait DomainError extends Product with Serializable:
  def message: String

object DomainError:

  /** La alerta solicitada no existe (o ya fue eliminada). */
  final case class AlertNotFound(id: AlertId) extends DomainError:
    def message: String = s"No existe la alerta con id ${id.value}"

  /** El umbral no cumple las reglas de [[pe.quiroz.fxalerts.domain.alert.Threshold]]. */
  final case class InvalidThreshold(value: BigDecimal, violation: ThresholdViolation)
      extends DomainError:
    def message: String = violation match
      case ThresholdViolation.NotPositive =>
        s"El umbral debe ser estrictamente positivo (recibido: $value)"
      case ThresholdViolation.TooManyDecimals =>
        s"El umbral admite como máximo ${ThresholdViolation.maxScale} decimales (recibido: $value)"
      case ThresholdViolation.TooLarge =>
        s"El umbral no puede superar ${ThresholdViolation.maxValue} (recibido: $value)"

  /**
   * El identificador de cliente no cumple las reglas de
   * [[pe.quiroz.fxalerts.domain.alert.ClientId]].
   */
  final case class InvalidClientId(value: String, violation: ClientIdViolation) extends DomainError:
    def message: String = violation match
      case ClientIdViolation.Blank =>
        "El identificador de cliente no puede estar vacío"
      case ClientIdViolation.TooLong =>
        s"El identificador de cliente admite como máximo ${ClientIdViolation.maxLength} caracteres"
