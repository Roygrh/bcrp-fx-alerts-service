package pe.quiroz.fxalerts.application.alert

import pe.quiroz.fxalerts.domain.DomainError.AlertNotFound
import pe.quiroz.fxalerts.domain.alert.{Alert, AlertId, ClientId}

/**
 * Puerto de salida: persistencia de alertas.
 *
 * El contrato habla en términos del dominio y hace explícita la ausencia: las lecturas devuelven
 * `Option` y las escrituras sobre una alerta concreta devuelven `Left(AlertNotFound)` cuando no
 * existe, en lugar de un contador de filas o una excepción. Cada operación es atómica por sí misma;
 * la coordinación entre operaciones es responsabilidad de [[AlertService]].
 *
 * Toda operación dirigida a una alerta concreta está acotada por su cliente propietario: una alerta
 * que existe pero pertenece a otro cliente es, para este contrato, una alerta que no existe. Así el
 * aislamiento entre clientes se aplica en la propia consulta y ningún adaptador ni servicio puede
 * omitirlo por descuido.
 *
 * Los fallos técnicos (conexión caída, violación de restricción por un defecto del código) se
 * propagan como errores del efecto `F`, ya que no son resultados esperables del negocio.
 */
trait AlertRepository[F[_]]:

  /** Persiste una alerta nueva. El identificador debe ser único. */
  def create(alert: Alert): F[Unit]

  /** Recupera una alerta del cliente indicado, o `None` si no existe o pertenece a otro cliente. */
  def findById(owner: ClientId, id: AlertId): F[Option[Alert]]

  /** Lista las alertas del cliente indicado ordenadas por fecha de creación ascendente. */
  def findAll(owner: ClientId): F[List[Alert]]

  /**
   * Reemplaza el estado persistido de la alerta identificada por `alert.id` y perteneciente a
   * `alert.clientId`.
   */
  def update(alert: Alert): F[Either[AlertNotFound, Unit]]

  /** Elimina la alerta indicada si pertenece al cliente indicado. */
  def delete(owner: ClientId, id: AlertId): F[Either[AlertNotFound, Unit]]
