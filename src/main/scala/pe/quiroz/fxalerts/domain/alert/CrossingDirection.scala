package pe.quiroz.fxalerts.domain.alert

/**
 * Sentido del cruce que dispara la alerta.
 *
 *   - `Above`: el tipo de cambio supera el umbral.
 *   - `Below`: el tipo de cambio cae por debajo del umbral.
 */
enum CrossingDirection:
  case Above, Below

  /**
   * `true` si `value` ha cruzado `threshold` en este sentido.
   *
   * La comparación es estricta en ambos sentidos: `Above` exige `value > threshold` y `Below` exige
   * `value < threshold`. Un valor exactamente igual al umbral no cruza en ningún sentido. El
   * criterio se fija aquí y no en cada consumidor por tres razones:
   *
   *   - Cruzar un umbral es rebasarlo. El umbral es la frontera que fijó el cliente; alcanzarla
   *     exactamente no es rebasarla. Con 3.80 de umbral, un tipo de cambio de 3.80 "está en" el
   *     umbral, no "por encima" ni "por debajo" de él.
   *   - Simetría. Con comparación estricta, un valor igual al umbral no dispara ni `Above` ni
   *     `Below`: dos alertas opuestas sobre el mismo umbral nunca se disparan a la vez sobre el
   *     mismo dato, y ningún sentido recibe un trato distinto. Si `Above` fuera `>=` y `Below` `<`
   *     (o al revés), la igualdad dispararía solo uno de los dos por una elección arbitraria que el
   *     cliente no controla ni ve.
   *   - Expresividad. El cliente que quiera la semántica inclusiva ("a partir de 3.80, inclusive")
   *     la expresa con el propio umbral: [[Threshold]] admite cuatro decimales y el dato oficial se
   *     publica con tres, de modo que `Above 3.7999` equivale a "mayor o igual que 3.800" sobre
   *     cualquier dato oficial. La opción inversa no existe: con una comparación no estricta no
   *     habría forma de pedir la exclusiva.
   *
   * La comparación es numérica y exacta (`BigDecimal`): `3.80` y `3.8` son el mismo valor, y no hay
   * errores de representación binaria que hagan que un dato "casi igual" cruce por accidente.
   */
  def crossed(value: BigDecimal, threshold: BigDecimal): Boolean =
    this match
      case Above => value > threshold
      case Below => value < threshold
