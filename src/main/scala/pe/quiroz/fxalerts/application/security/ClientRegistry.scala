package pe.quiroz.fxalerts.application.security

import pe.quiroz.fxalerts.domain.alert.ClientId

/**
 * Puerto de salida: registro de clientes autorizados a obtener tokens.
 *
 * Solo se necesita la búsqueda por identificador. El alta y la baja de clientes son un problema
 * distinto (hoy se resuelven por configuración) y no forman parte del contrato.
 */
trait ClientRegistry[F[_]]:

  /** Recupera el cliente registrado con ese identificador, o `None` si no existe. */
  def find(id: ClientId): F[Option[RegisteredClient]]
