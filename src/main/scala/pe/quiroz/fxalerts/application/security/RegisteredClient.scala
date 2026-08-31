package pe.quiroz.fxalerts.application.security

import pe.quiroz.fxalerts.domain.alert.ClientId

/**
 * Forma almacenable del secreto de un cliente: el resultado de una función de derivación de claves
 * junto con sus parámetros, nunca el secreto en claro.
 *
 * Es opaco para la capa de aplicación: qué función se usó y cómo se codifica lo decide el adaptador
 * de [[SecretHasher]], que es el único capaz de producir y verificar estos valores.
 */
opaque type SecretHash = String

object SecretHash:

  def from(encoded: String): Either[String, SecretHash] =
    val trimmed = encoded.trim
    if trimmed.isEmpty then Left("El hash del secreto no puede estar vacío") else Right(trimmed)

  extension (hash: SecretHash) def encoded: String = hash

/**
 * Cliente autorizado a solicitar tokens.
 *
 * El identificador es el mismo [[ClientId]] con el que se registran las alertas: el sujeto del
 * token identifica al cliente comercial propietario de sus recursos.
 *
 * @param id
 *   identificador público del cliente (`client_id`)
 * @param secretHash
 *   secreto del cliente en forma derivada; el secreto en claro solo lo conoce el cliente
 * @param scopes
 *   alcances que el cliente tiene concedidos; un token nunca puede llevar otros
 */
final case class RegisteredClient(id: ClientId, secretHash: SecretHash, scopes: Set[Scope])
