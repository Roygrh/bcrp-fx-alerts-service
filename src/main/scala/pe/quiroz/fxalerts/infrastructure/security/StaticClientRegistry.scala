package pe.quiroz.fxalerts.infrastructure.security

import cats.Applicative
import cats.data.NonEmptyList
import cats.syntax.all.*
import pe.quiroz.fxalerts.application.security.{ClientRegistry, RegisteredClient}
import pe.quiroz.fxalerts.domain.alert.ClientId

/**
 * Adaptador de [[ClientRegistry]] sobre la lista de clientes cargada de la configuración.
 *
 * Es inmutable: dar de alta o de baja un cliente exige reiniciar el servicio. Es una limitación
 * asumida en este alcance; un registro persistente con alta, rotación de secretos y revocación es
 * un problema aparte.
 */
final class StaticClientRegistry[F[_]: Applicative](clients: NonEmptyList[RegisteredClient])
    extends ClientRegistry[F]:

  private val byId: Map[ClientId, RegisteredClient] =
    clients.toList.map(client => client.id -> client).toMap

  def find(id: ClientId): F[Option[RegisteredClient]] = byId.get(id).pure[F]

  def size: Int = byId.size
