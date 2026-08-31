package pe.quiroz.fxalerts.infrastructure.persistence

import cats.effect.MonadCancelThrow
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import pe.quiroz.fxalerts.application.alert.AlertRepository
import pe.quiroz.fxalerts.domain.DomainError.AlertNotFound
import pe.quiroz.fxalerts.domain.alert.{Alert, AlertId, ClientId}
import pe.quiroz.fxalerts.infrastructure.persistence.AlertMeta.given

/**
 * Adaptador de [[AlertRepository]] sobre PostgreSQL con doobie.
 *
 * Cada operación se ejecuta en su propia transacción. Las escrituras dirigidas a una alerta
 * concreta consultan el número de filas afectadas para traducir la ausencia a `AlertNotFound`.
 *
 * El acotamiento por cliente propietario forma parte del `WHERE` de cada consulta (`client_id`), no
 * de un filtrado posterior en memoria: la base de datos nunca devuelve filas ajenas al cliente y no
 * existe ventana entre comprobar la propiedad y actuar sobre la fila.
 */
final class DoobieAlertRepository[F[_]: MonadCancelThrow](transactor: Transactor[F])
    extends AlertRepository[F]:

  import DoobieAlertRepository.selectAlert

  def create(alert: Alert): F[Unit] =
    sql"""
      INSERT INTO alerts (id, client_id, series_code, threshold, direction, status, created_at, updated_at)
      VALUES (
        ${alert.id}, ${alert.clientId}, ${alert.series}, ${alert.threshold},
        ${alert.direction}, ${alert.status}, ${alert.createdAt}, ${alert.updatedAt}
      )
    """.update.run.transact(transactor).void

  def findById(owner: ClientId, id: AlertId): F[Option[Alert]] =
    (selectAlert ++ fr"WHERE id = $id AND client_id = $owner")
      .query[Alert]
      .option
      .transact(transactor)

  def findAll(owner: ClientId): F[List[Alert]] =
    (selectAlert ++ fr"WHERE client_id = $owner ORDER BY created_at, id")
      .query[Alert]
      .to[List]
      .transact(transactor)

  def update(alert: Alert): F[Either[AlertNotFound, Unit]] =
    sql"""
      UPDATE alerts
      SET series_code = ${alert.series},
          threshold   = ${alert.threshold},
          direction   = ${alert.direction},
          status      = ${alert.status},
          updated_at  = ${alert.updatedAt}
      WHERE id = ${alert.id} AND client_id = ${alert.clientId}
    """.update.run
      .map(affected => Either.cond(affected == 1, (), AlertNotFound(alert.id)))
      .transact(transactor)

  def delete(owner: ClientId, id: AlertId): F[Either[AlertNotFound, Unit]] =
    sql"DELETE FROM alerts WHERE id = $id AND client_id = $owner".update.run
      .map(affected => Either.cond(affected == 1, (), AlertNotFound(id)))
      .transact(transactor)

object DoobieAlertRepository:

  /**
   * Proyección completa de la tabla en el orden de los campos de [[Alert]].
   *
   * doobie deriva `Read[Alert]` posicionalmente a partir de las instancias `Meta` de [[AlertMeta]];
   * por eso el orden de las columnas debe coincidir con el de los campos de la case class.
   */
  private val selectAlert: Fragment =
    fr"""
      SELECT id, client_id, series_code, threshold, direction, status, created_at, updated_at
      FROM alerts
    """
