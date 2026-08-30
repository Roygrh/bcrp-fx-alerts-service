package pe.quiroz.fxalerts.domain.alert

/**
 * Serie estadística del BCRP que una alerta observa.
 *
 * Catálogo cerrado: solo las series aquí enumeradas pueden asociarse a una alerta, y cada una
 * conoce su código oficial en la API de estadísticas del BCRP. Incorporar una serie nueva es añadir
 * un caso; la base de datos almacena el código, no el nombre del caso, para que el catálogo pueda
 * crecer sin migraciones.
 *
 * @param code
 *   código de la serie en la API del BCRP
 * @param description
 *   nombre oficial de la serie
 */
enum BcrpSeries(val code: String, val description: String):

  /** Tipo de cambio venta del sistema bancario (SBS), soles por dólar, frecuencia diaria. */
  case UsdPenSbsSell extends BcrpSeries("PD04640PD", "TC Sistema bancario SBS (S/ por US$) - Venta")

object BcrpSeries:
  def fromCode(code: String): Option[BcrpSeries] = values.find(_.code == code)
