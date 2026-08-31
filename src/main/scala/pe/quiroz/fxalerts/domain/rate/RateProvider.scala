package pe.quiroz.fxalerts.domain.rate

/**
 * Procedencia de un tipo de cambio: quién lo produjo, si es oficial y qué mide.
 *
 * Las fuentes de las que se nutre el servicio no miden lo mismo, y el negocio debe poder
 * distinguirlas sin conocer la infraestructura. Por eso la procedencia es un concepto de dominio y
 * viaja con cada valor ([[ExchangeRate.provider]]): un precio oficial de referencia y una tasa de
 * mercado indicativa no son intercambiables, aunque ambos se expresen en soles por dólar.
 *
 * Catálogo cerrado: incorporar una fuente es añadir un caso y decidir explícitamente si es oficial.
 *
 * @param code
 *   identificador estable, usado en la configuración y en el contrato HTTP
 * @param name
 *   nombre legible de la fuente
 * @param official
 *   `true` si el valor es un precio oficial de referencia del sistema financiero peruano
 * @param measures
 *   descripción breve de qué representa el valor
 */
enum RateProvider(
    val code: String,
    val name: String,
    val official: Boolean,
    val measures: String
):

  /**
   * Serie PD04640PD de BCRPData: tipo de cambio venta del sistema bancario, producido por la SBS
   * (Superintendencia de Banca, Seguros y AFP) y distribuido por el BCRP.
   */
  case Bcrp
      extends RateProvider(
        code = "BCRP",
        name = "BCRPData (BCRP) - serie PD04640PD elaborada por la SBS",
        official = true,
        measures = "Tipo de cambio venta del sistema bancario (S/ por US$) publicado por la SBS; " +
          "precio oficial de referencia del sistema financiero peruano"
      )

  /** ExchangeRate-API (open.er-api.com): tasa de mercado agregada por un proveedor comercial. */
  case ExchangeRateApi
      extends RateProvider(
        code = "ERAPI",
        name = "ExchangeRate-API",
        official = false,
        measures = "Tasa de mercado USD/PEN agregada por un proveedor comercial; " +
          "referencia indicativa, no oficial"
      )

object RateProvider:

  /** Busca por código sin distinguir mayúsculas de minúsculas. */
  def fromCode(code: String): Option[RateProvider] =
    values.find(_.code.equalsIgnoreCase(code.trim))
