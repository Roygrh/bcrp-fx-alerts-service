package pe.quiroz.fxalerts.application.health

/** Estado operativo de un componente del servicio. */
enum ComponentStatus:
  case Up, Down

/**
 * Resultado de la verificación de salud del servicio.
 *
 * @param status
 *   estado agregado: `Up` solo si todos los componentes están `Up`
 * @param database
 *   estado de la conexión a la base de datos
 */
final case class HealthReport(status: ComponentStatus, database: ComponentStatus)

object HealthReport:
  def fromComponents(database: ComponentStatus): HealthReport =
    HealthReport(status = database, database = database)
