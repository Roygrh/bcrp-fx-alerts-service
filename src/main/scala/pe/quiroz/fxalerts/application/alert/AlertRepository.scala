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
 * Los fallos técnicos (conexión caída, violación de restricción por un defecto del código) se
 * propagan como errores del efecto `F`, ya que no son resultados esperables del negocio.
 */
trait AlertRepository[F[_]]:

  /** Persiste una alerta nueva. El identificador debe ser único. */
  def create(alert: Alert): F[Unit]

  /** Recupera una alerta por su identificador, o `None` si no existe. */
  def findById(id: AlertId): F[Option[Alert]]

  /**
   * Lista alertas ordenadas por fecha de creación ascendente.
   *
   * @param clientId
   *   si se indica, restringe el resultado a las alertas de ese cliente
   */
  def findAll(clientId: Option[ClientId]): F[List[Alert]]

  /** Reemplaza el estado persistido de la alerta identificada por `alert.id`. */
  def update(alert: Alert): F[Either[AlertNotFound, Unit]]

  /** Elimina la alerta indicada. */
  def delete(id: AlertId): F[Either[AlertNotFound, Unit]]
