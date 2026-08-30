package pe.quiroz.fxalerts.domain.alert

import pe.quiroz.fxalerts.domain.DomainError

/** Regla incumplida al construir un [[Threshold]]. */
enum ThresholdViolation:
  case NotPositive, TooManyDecimals, TooLarge

object ThresholdViolation:
  /**
   * Número máximo de decimales. El BCRP publica el tipo de cambio con tres decimales (por ejemplo,
   * 3.756) y el mercado interbancario cotiza con cuatro (3.7565); un quinto decimal no tiene
   * significado para el negocio y solo introduciría umbrales que nunca podrían compararse con
   * exactitud contra el dato oficial.
   */
  val maxScale: Int = 4

  /**
   * Valor máximo admitido. Corresponde a `NUMERIC(10,4)` en la base de datos (seis dígitos
   * enteros): muy por encima de cualquier tipo de cambio plausible, pero acotado para que el
   * dominio nunca acepte un valor que la columna rechazaría o redondearía.
   */
  val maxValue: BigDecimal = BigDecimal("999999.9999")

/**
 * Umbral de tipo de cambio de una alerta.
 *
 * Se modela como tipo opaco sobre `BigDecimal`, nunca sobre `Double`/`Float`: el tipo de cambio es
 * un importe monetario y la comparación "supera S/ 3.80" debe ser exacta, sin errores de
 * representación binaria. El valor se guarda en forma canónica (sin ceros a la derecha), de modo
 * que dos umbrales iguales tienen la misma representación textual.
 */
opaque type Threshold = BigDecimal

object Threshold:

  def from(raw: BigDecimal): Either[DomainError.InvalidThreshold, Threshold] =
    val value = canonical(raw)
    if value <= 0 then Left(DomainError.InvalidThreshold(raw, ThresholdViolation.NotPositive))
    else if value.scale > ThresholdViolation.maxScale then
      Left(DomainError.InvalidThreshold(raw, ThresholdViolation.TooManyDecimals))
    else if value > ThresholdViolation.maxValue then
      Left(DomainError.InvalidThreshold(raw, ThresholdViolation.TooLarge))
    else Right(value)

  /**
   * Elimina ceros a la derecha conservando la parte entera en notación plana (`100`, no `1E+2`).
   */
  private def canonical(raw: BigDecimal): BigDecimal =
    val stripped = raw.bigDecimal.stripTrailingZeros
    BigDecimal(if stripped.scale < 0 then stripped.setScale(0) else stripped)

  extension (threshold: Threshold) def value: BigDecimal = threshold
