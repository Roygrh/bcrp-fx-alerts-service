package pe.quiroz.fxalerts.application.alert

import cats.effect.{IO, Ref}
import pe.quiroz.fxalerts.domain.DomainError.AlertNotFound
import pe.quiroz.fxalerts.domain.alert.{Alert, AlertId, ClientId}

/**
 * Doble de [[AlertRepository]] respaldado por un `Ref`, para probar el servicio sin base de datos.
 * Respeta el contrato del puerto, incluidos el orden del listado y el acotamiento por propietario.
 */
final class InMemoryAlertRepository(state: Ref[IO, Map[AlertId, Alert]])
    extends AlertRepository[IO]:

  def create(alert: Alert): IO[Unit] =
    state.update(_ + (alert.id -> alert))

  def findById(owner: ClientId, id: AlertId): IO[Option[Alert]] =
    state.get.map(_.get(id).filter(_.clientId == owner))

  def findAll(owner: ClientId): IO[List[Alert]] =
    state.get.map { alerts =>
      alerts.values.toList
        .filter(_.clientId == owner)
        .sortBy(alert => (alert.createdAt, alert.id.value))
    }

  def update(alert: Alert): IO[Either[AlertNotFound, Unit]] =
    state.modify { alerts =>
      if alerts.get(alert.id).exists(_.clientId == alert.clientId) then
        (alerts + (alert.id -> alert), Right(()))
      else (alerts, Left(AlertNotFound(alert.id)))
    }

  def delete(owner: ClientId, id: AlertId): IO[Either[AlertNotFound, Unit]] =
    state.modify { alerts =>
      if alerts.get(id).exists(_.clientId == owner) then (alerts - id, Right(()))
      else (alerts, Left(AlertNotFound(id)))
    }

  /** Acceso sin acotar, solo para que las pruebas comprueben el estado real del almacén. */
  def all: IO[List[Alert]] = state.get.map(_.values.toList)

object InMemoryAlertRepository:
  def empty: IO[InMemoryAlertRepository] =
    Ref.of[IO, Map[AlertId, Alert]](Map.empty).map(new InMemoryAlertRepository(_))
