package pe.quiroz.fxalerts.application.security

import pe.quiroz.fxalerts.domain.alert.ClientId

import java.time.Instant
import java.util.UUID

/**
 * Contenido de un token de acceso, independiente de su serialización.
 *
 * Son los claims registrados de RFC 7519 más `scope` (RFC 8693 §4.2). La capa de aplicación los
 * decide y el adaptador de [[TokenIssuer]] los firma; el adaptador de [[TokenVerifier]] los
 * comprueba y devuelve solo lo que la autorización necesita ([[AuthenticatedClient]]).
 *
 * @param issuer
 *   `iss`: quién emitió el token
 * @param subject
 *   `sub`: el cliente al que se emitió
 * @param audience
 *   `aud`: para qué API es válido
 * @param issuedAt
 *   `iat`: instante de emisión
 * @param expiresAt
 *   `exp`: instante a partir del cual deja de ser válido
 * @param tokenId
 *   `jti`: identificador único del token
 * @param scopes
 *   `scope`: alcances autorizados
 */
final case class AccessTokenClaims(
    issuer: String,
    subject: ClientId,
    audience: String,
    issuedAt: Instant,
    expiresAt: Instant,
    tokenId: UUID,
    scopes: Set[Scope]
)

/**
 * Identidad establecida a partir de un token válido: es lo único que los endpoints protegidos
 * conocen de quien los invoca.
 */
final case class AuthenticatedClient(clientId: ClientId, scopes: Set[Scope], tokenId: UUID):
  def has(scope: Scope): Boolean = scopes.contains(scope)

/**
 * Motivo por el que un token se rechaza.
 *
 * Existe para el log y las pruebas: hacia el cliente HTTP todos los motivos se responden igual, de
 * modo que nadie pueda distinguir desde fuera un token caducado de uno con firma inválida.
 */
enum TokenRejection:
  case Malformed, InvalidSignature, Expired, NotYetValid, WrongIssuer, WrongAudience,
    InvalidSubject, UnknownScope
