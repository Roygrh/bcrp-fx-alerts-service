package pe.quiroz.fxalerts.application.security

import scala.concurrent.duration.FiniteDuration

/** Credenciales presentadas por un cliente, sea por cabecera HTTP Basic o por el cuerpo. */
final case class ClientCredentials(clientId: String, secret: String)

/**
 * Solicitud de token tal como llega del transporte, ya separada de sus mecanismos HTTP.
 *
 * Los campos viajan sin validar: es [[TokenService]] quien decide si el tipo de concesión se
 * admite, si las credenciales son correctas y si los alcances pedidos están concedidos.
 *
 * @param grantType
 *   valor de `grant_type`, si se envió
 * @param credentials
 *   credenciales del cliente, si se enviaron por alguno de los mecanismos admitidos
 * @param scope
 *   alcances solicitados separados por espacio, si se enviaron
 */
final case class TokenRequest(
    grantType: Option[String],
    credentials: Option[ClientCredentials],
    scope: Option[String]
)

/**
 * Errores de una solicitud de token, con los códigos que define RFC 6749 §5.2.
 *
 * `invalid_grant` no figura porque solo aplica a concesiones con un artefacto previo (código de
 * autorización o token de refresco), que este servicio no emite. `error_description` se limita al
 * juego de caracteres que exige la RFC (ASCII imprimible sin comillas ni barra invertida).
 */
sealed trait TokenRequestError extends Product with Serializable:
  def code: String
  def description: String

object TokenRequestError:

  /** Falta un parámetro obligatorio, se repite o se usan dos mecanismos de autenticación. */
  final case class InvalidRequest(description: String) extends TokenRequestError:
    def code: String = "invalid_request"

  /**
   * Cliente desconocido, secreto incorrecto o credenciales ausentes. Un único caso a propósito: la
   * respuesta no debe permitir averiguar qué identificadores de cliente existen.
   */
  case object InvalidClient extends TokenRequestError:
    def code: String        = "invalid_client"
    def description: String = "Las credenciales del cliente no son correctas"

  final case class UnsupportedGrantType(received: String) extends TokenRequestError:
    def code: String        = "unsupported_grant_type"
    def description: String = "Solo se admite grant_type=client_credentials"

  /** Alcances desconocidos o no concedidos a este cliente. */
  final case class InvalidScope(rejected: List[String]) extends TokenRequestError:
    def code: String        = "invalid_scope"
    def description: String =
      "Alcances desconocidos o no concedidos a este cliente: " +
        printable(rejected.mkString(" "))

  private val maxEchoLength = 120

  /**
   * Restringe un texto procedente de la petición al juego de caracteres de `error_description` (RFC
   * 6749 §5.2) y lo acota, para no reflejar texto arbitrario del cliente.
   */
  private def printable(raw: String): String =
    raw.filter(c => c >= ' ' && c <= '~' && c != '"' && c != '\\').take(maxEchoLength)

/**
 * Token emitido junto con los datos que la respuesta HTTP debe comunicar (RFC 6749 §5.1).
 *
 * @param accessToken
 *   token en forma compacta
 * @param expiresIn
 *   vida del token desde su emisión
 * @param scopes
 *   alcances efectivamente concedidos; se comunican siempre, coincidan o no con los pedidos
 */
final case class IssuedToken(accessToken: String, expiresIn: FiniteDuration, scopes: Set[Scope])
