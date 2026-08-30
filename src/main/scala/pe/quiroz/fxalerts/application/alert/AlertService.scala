package pe.quiroz.fxalerts.application.alert

import cats.Monad
import cats.data.EitherT
import cats.effect.Clock
import cats.effect.std.UUIDGen
import cats.syntax.all.*
import pe.quiroz.fxalerts.domain.DomainError
import pe.quiroz.fxalerts.domain.DomainError.AlertNotFound
import pe.quiroz.fxalerts.domain.alert.{Alert, AlertId, ClientId}

/**
 * Servicio de aplicación para el ciclo de vida de las alertas.
 *
 * Orquesta el repositorio y delega las reglas de negocio al dominio: genera identificadores y
 * marcas de tiempo (los únicos efectos que el dominio no puede producir), invoca los constructores
 * inteligentes y traduce la ausencia en el repositorio al error de dominio correspondiente. Ningún
 * método lanza excepciones por causas de negocio: todo resultado esperable se expresa en
 * `Either[DomainError, _]`.
 */
final class AlertService[F[_]: Monad: Clock: UUIDGen](repository: AlertRepository[F]):

  def create(command: CreateAlert): F[Either[DomainError, Alert]] =
    for
      id  <- UUIDGen[F].randomUUID.map(AlertId(_))
      now <- Clock[F].realTimeInstant
      created = Alert.create(
        id = id,
        clientId = command.clientId,
        series = command.series,
        threshold = command.threshold,
        direction = command.direction,
        createdAt = now
      )
      result <- created.traverse(alert => repository.create(alert).as(alert))
    yield result

  def get(id: AlertId): F[Either[DomainError, Alert]] =
    repository.findById(id).map(_.toRight(AlertNotFound(id)))

  def list(clientId: Option[ClientId]): F[List[Alert]] =
    repository.findAll(clientId)

  /**
   * Reemplaza la configuración de una alerta existente.
   *
   * Si la alerta desaparece entre la lectura y la escritura, el repositorio lo reporta y el
   * resultado es igualmente `AlertNotFound`, sin excepciones.
   */
  def update(id: AlertId, command: UpdateAlert): F[Either[DomainError, Alert]] =
    val updated = for
      existing <- EitherT(get(id))
      now      <- EitherT.liftF(Clock[F].realTimeInstant)
      alert    <- EitherT.fromEither[F](
        existing.update(
          series = command.series,
          threshold = command.threshold,
          direction = command.direction,
          status = command.status,
          updatedAt = now
        )
      )
      _ <- EitherT(repository.update(alert)).leftWiden[DomainError]
    yield alert
    updated.value

  def delete(id: AlertId): F[Either[DomainError, Unit]] =
    repository.delete(id).map(_.leftWiden[DomainError])
