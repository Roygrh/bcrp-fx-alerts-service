package pe.quiroz.fxalerts.domain.alert

/**
 * Sentido del cruce que dispara la alerta.
 *
 *   - `Above`: el tipo de cambio supera el umbral.
 *   - `Below`: el tipo de cambio cae por debajo del umbral.
 */
enum CrossingDirection:
  case Above, Below
