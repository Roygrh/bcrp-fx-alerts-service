package pe.quiroz.fxalerts.domain

/**
 * Raíz de la jerarquía de errores de dominio.
 *
 * Las variantes concretas (alerta inexistente, umbral inválido, etc.) se incorporan junto con el
 * modelo de alertas. Se mantiene sellada para que el compilador exija exhaustividad al mapear
 * errores de dominio a respuestas HTTP.
 */
sealed trait DomainError extends Product with Serializable:
  def message: String
