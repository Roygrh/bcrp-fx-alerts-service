package pe.quiroz.fxalerts.application.health

import pe.quiroz.fxalerts.domain.rate.RateProvider

/**
 * Estado operativo de un componente o del servicio en conjunto.
 *
 *   - `Up`: opera con normalidad.
 *   - `Degraded`: sigue prestando servicio, pero con una capacidad mermada (por ejemplo, sirviendo
 *     datos en caché o desde una fuente de respaldo no oficial).
 *   - `Down`: no puede prestar el servicio.
 */
enum ComponentStatus:
  case Up, Degraded, Down

/**
 * Estado de un componente con una explicación breve, apta para mostrar al operador.
 *
 * @param detail
 *   descripción no técnica de la situación; se omite cuando no aporta nada ("UP" se explica solo)
 */
final case class ComponentHealth(status: ComponentStatus, detail: Option[String] = None)

/**
 * Estado de la obtención del tipo de cambio.
 *
 * Además del estado, informa qué fuente está sirviendo el dato en este momento: con más de una
 * fuente posible, "UP" no basta para saber si el servicio opera sobre el precio oficial o sobre una
 * referencia de mercado.
 *
 * @param provider
 *   fuente que entregó el último dato servido, si hay alguno
 */
final case class RateHealth(
    status: ComponentStatus,
    detail: Option[String],
    provider: Option[RateProvider]
)

/**
 * Resultado de la verificación de salud del servicio.
 *
 * @param status
 *   estado agregado según el criterio de [[HealthReport.fromComponents]]
 * @param database
 *   estado de la conexión a la base de datos
 * @param rates
 *   estado de la obtención del tipo de cambio (cadena de fuentes y su caché)
 */
final case class HealthReport(
    status: ComponentStatus,
    database: ComponentHealth,
    rates: RateHealth
)

object HealthReport:

  /**
   * Criterio de agregación:
   *
   *   - `Down` si la base de datos está `Down`: sin ella no funciona ninguna capacidad del servicio
   *     (las alertas viven ahí), así que reiniciar o retirar la instancia es razonable.
   *   - `Up` si todos los componentes están `Up`.
   *   - `Degraded` en cualquier otro caso: en particular, que ninguna fuente de tipo de cambio
   *     responda, o que se esté sirviendo desde caché o desde el respaldo no oficial, no derriba el
   *     servicio: el CRUD de alertas sigue operativo y reiniciar la instancia no arreglaría nada.
   */
  def fromComponents(database: ComponentHealth, rates: RateHealth): HealthReport =
    val aggregate =
      if database.status == ComponentStatus.Down then ComponentStatus.Down
      else if database.status == ComponentStatus.Up && rates.status == ComponentStatus.Up then
        ComponentStatus.Up
      else ComponentStatus.Degraded
    HealthReport(status = aggregate, database = database, rates = rates)
