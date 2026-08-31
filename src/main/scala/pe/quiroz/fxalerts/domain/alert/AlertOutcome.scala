package pe.quiroz.fxalerts.domain.alert

/**
 * Resultado de evaluar una alerta contra un valor de tipo de cambio.
 *
 * Distingue "evaluada y no disparada" de "no evaluada": para quien consume el resultado no es lo
 * mismo que el tipo de cambio no haya cruzado el umbral a que la regla no se haya aplicado. Solo
 * `Triggered` y `NotTriggered` son el resultado de aplicar la regla de cruce; los otros dos casos
 * explican por qué no se aplicó.
 *
 * Este resultado dice únicamente si el valor cruzó el umbral. La calidad del valor sobre el que se
 * decidió (oficial o no, confirmado o no) es una dimensión independiente y viaja aparte
 * ([[EvaluationBasis]]): una alerta disparada sobre una referencia de mercado es `Triggered` igual
 * que una disparada sobre el precio oficial, y es la base de la evaluación la que las distingue.
 */
enum AlertOutcome:

  /** Alerta activa, de la serie del dato, cuyo umbral el valor cruzó en el sentido configurado. */
  case Triggered

  /** Alerta activa, de la serie del dato, cuyo umbral el valor no cruzó. */
  case NotTriggered

  /** Alerta inactiva: no se evalúa nunca, cualquiera que sea el valor. */
  case Inactive

  /**
   * La alerta observa una serie distinta de la del dato: no se evalúa, porque comparar el umbral de
   * una serie con el valor de otra no tiene significado.
   */
  case SeriesMismatch

  def triggered: Boolean = this == Triggered

  /** `true` si la regla de cruce llegó a aplicarse (`Triggered` o `NotTriggered`). */
  def evaluated: Boolean = this == Triggered || this == NotTriggered
