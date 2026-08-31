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
 *
 * Toda operación recibe el cliente en cuyo nombre se ejecuta (`owner`): la identidad autenticada
 * por la capa de entrada. El servicio solo opera sobre las alertas de ese cliente; una alerta de
 * otro cliente se reporta como inexistente ([[AlertNotFound]]), no como prohibida, para que ningún
 * cliente pueda averiguar qué identificadores existen fuera de su ámbito.
 */
final class AlertService[F[_]: Monad: Clock: UUIDGen](repository: AlertRepository[F]):

  def create(owner: ClientId, command: CreateAlert): F[Either[DomainError, Alert]] =
    for
      id  <- UUIDGen[F].randomUUID.map(AlertId(_))
      now <- Clock[F].realTimeInstant
      created = Alert.create(
        id = id,
        clientId = owner,
        series = command.series,
        threshold = command.threshold,
        direction = command.direction,
        createdAt = now
      )
      result <- created.traverse(alert => repository.create(alert).as(alert))
    yield result

  def get(owner: ClientId, id: AlertId): F[Either[DomainError, Alert]] =
    repository.findById(owner, id).map(_.toRight(AlertNotFound(id)))

  def list(owner: ClientId): F[List[Alert]] =
    repository.findAll(owner)

  /**
   * Reemplaza la configuración de una alerta existente del cliente.
   *
   * Si la alerta desaparece entre la lectura y la escritura, el repositorio lo reporta y el
   * resultado es igualmente `AlertNotFound`, sin excepciones.
   */
  def update(owner: ClientId, id: AlertId, command: UpdateAlert): F[Either[DomainError, Alert]] =
    val updated = for
      existing <- EitherT(get(owner, id))
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

  def delete(owner: ClientId, id: AlertId): F[Either[DomainError, Unit]] =
    repository.delete(owner, id).map(_.leftWiden[DomainError])
