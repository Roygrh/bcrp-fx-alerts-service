package pe.quiroz.fxalerts.domain

import pe.quiroz.fxalerts.domain.alert.{AlertId, BcrpSeries, ClientIdViolation, ThresholdViolation}

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

  /**
   * Resultados no exitosos de la consulta del tipo de cambio.
   *
   * Sub-jerarquía sellada para que el puerto
   * [[pe.quiroz.fxalerts.application.rate.ExchangeRateSource]] pueda declarar en su firma
   * exactamente estos dos casos y, a la vez, sigan siendo errores de dominio con traducción HTTP
   * exhaustiva.
   */
  sealed trait ExchangeRateError extends DomainError:
    def series: BcrpSeries

  /**
   * La fuente respondió correctamente pero no hay valor publicado en la ventana consultada (días no
   * hábiles, serie sin datos recientes).
   */
  final case class ExchangeRateNotPublished(series: BcrpSeries) extends ExchangeRateError:
    def message: String =
      s"No hay un tipo de cambio publicado para la serie ${series.code} en el periodo consultado"

  /**
   * La fuente no pudo consultarse y no existe ningún valor, ni siquiera obsoleto, que ofrecer.
   */
  final case class ExchangeRateUnavailable(series: BcrpSeries) extends ExchangeRateError:
    def message: String =
      s"El tipo de cambio de la serie ${series.code} no está disponible en este momento; " +
        "inténtelo más tarde"
