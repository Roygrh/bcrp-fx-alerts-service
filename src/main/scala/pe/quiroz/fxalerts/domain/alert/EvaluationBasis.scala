package pe.quiroz.fxalerts.domain.alert

import pe.quiroz.fxalerts.domain.rate.ExchangeRate

/**
 * Calidad del dato sobre el que se evaluó una alerta.
 *
 * El resultado de la evaluación ([[AlertOutcome]]) dice si el valor cruzó el umbral; esta
 * enumeración dice cuánto vale ese valor como fundamento para actuar. Son dimensiones
 * independientes y por eso no se mezclan en un único enumerado: "disparada" es siempre "disparada",
 * y la base es la que permite a un consumidor decidir si una alerta disparada sobre una referencia
 * de mercado, o sobre un dato que ninguna fuente pudo confirmar, merece la misma reacción que una
 * disparada sobre el precio oficial confirmado.
 *
 * Los tres casos son excluyentes y están ordenados de mayor a menor confianza. `conclusive` resume
 * la decisión para un sistema que actúe automáticamente: solo el precio oficial confirmado por su
 * fuente dentro de su periodo de validez es concluyente. El servicio no aplica ninguna política
 * sobre los casos no concluyentes (no oculta ni degrada la alerta): expone la información con
 * precisión y deja la decisión a quien consume el resultado.
 *
 * @param conclusive
 *   `true` si el dato es el precio oficial de referencia y su fuente lo confirmó dentro del periodo
 *   de validez
 */
enum EvaluationBasis(val conclusive: Boolean):

  /**
   * Precio oficial de referencia (ver [[pe.quiroz.fxalerts.domain.rate.RateProvider.official]])
   * confirmado por su fuente dentro del periodo de validez. Es la única base concluyente.
   */
  case OfficialConfirmed extends EvaluationBasis(conclusive = true)

  /**
   * Tasa de mercado indicativa de una fuente no oficial, confirmada dentro de su periodo de
   * validez. El valor es reciente, pero no es el precio oficial ni mide exactamente lo mismo: una
   * alerta disparada sobre esta base puede no dispararse sobre el dato oficial, y al revés.
   */
  case MarketReference extends EvaluationBasis(conclusive = false)

  /**
   * Último valor conocido, entregado porque ninguna fuente respondió; ninguna pudo confirmarlo
   * dentro de su periodo de validez. Se aplica sea cual sea la procedencia del valor: un dato
   * oficial que ya nadie confirma no es mejor fundamento que una referencia de mercado vigente, y
   * por eso la falta de confirmación prevalece sobre la oficialidad.
   */
  case Unconfirmed extends EvaluationBasis(conclusive = false)

object EvaluationBasis:

  /**
   * Determina la base a partir de la procedencia del dato y de si su fuente lo confirmó dentro del
   * periodo de validez. La falta de confirmación prevalece sobre la oficialidad.
   *
   * @param confirmed
   *   `true` si la fuente entregó o ratificó el valor dentro de su periodo de validez; `false` si
   *   se trata del último valor conocido, servido porque ninguna fuente responde
   */
  def of(rate: ExchangeRate, confirmed: Boolean): EvaluationBasis =
    if !confirmed then Unconfirmed
    else if rate.official then OfficialConfirmed
    else MarketReference
