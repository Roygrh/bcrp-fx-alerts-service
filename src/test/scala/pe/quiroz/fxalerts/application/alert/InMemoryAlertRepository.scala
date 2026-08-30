package pe.quiroz.fxalerts.application.alert

import cats.effect.{IO, Ref}
import pe.quiroz.fxalerts.domain.DomainError.AlertNotFound
import pe.quiroz.fxalerts.domain.alert.{Alert, AlertId, ClientId}

/**
 * Doble de [[AlertRepository]] respaldado por un `Ref`, para probar el servicio sin base de datos.
 * Respeta el contrato del puerto, incluido el orden del listado.
 */
final class InMemoryAlertRepository(state: Ref[IO, Map[AlertId, Alert]])
    extends AlertRepository[IO]:

  def create(alert: Alert): IO[Unit] =
    state.update(_ + (alert.id -> alert))

  def findById(id: AlertId): IO[Option[Alert]] =
    state.get.map(_.get(id))

  def findAll(clientId: Option[ClientId]): IO[List[Alert]] =
    state.get.map { alerts =>
      alerts.values.toList
        .filter(alert => clientId.forall(_ == alert.clientId))
        .sortBy(alert => (alert.createdAt, alert.id.value))
    }

  def update(alert: Alert): IO[Either[AlertNotFound, Unit]] =
    state.modify { alerts =>
      if alerts.contains(alert.id) then (alerts + (alert.id -> alert), Right(()))
      else (alerts, Left(AlertNotFound(alert.id)))
    }

  def delete(id: AlertId): IO[Either[AlertNotFound, Unit]] =
    state.modify { alerts =>
      if alerts.contains(id) then (alerts - id, Right(()))
      else (alerts, Left(AlertNotFound(id)))
    }

object InMemoryAlertRepository:
  def empty: IO[InMemoryAlertRepository] =
    Ref.of[IO, Map[AlertId, Alert]](Map.empty).map(new InMemoryAlertRepository(_))
