package pe.quiroz.fxalerts.infrastructure.http.auth

import cats.effect.IO
import org.http4s.headers.Authorization
import org.http4s.{AuthScheme, Credentials, Request}
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import pe.quiroz.fxalerts.application.security.{AccessTokenClaims, Scope}
import pe.quiroz.fxalerts.domain.alert.ClientId
import pe.quiroz.fxalerts.infrastructure.security.TestKeys
import pe.quiroz.fxalerts.infrastructure.security.jwt.{JwtTokens, Rs256Jwt}

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*

/**
 * Emisor de tokens para las suites HTTP: firma con las claves de prueba y ofrece la
 * [[BearerAuthentication]] que las rutas necesitan, configurada con el mismo emisor y audiencia.
 */
object TestTokens:

  private given Logger[IO] = Slf4jLogger.getLogger[IO]

  val issuer: String   = "fx-alerts-pruebas"
  val audience: String = "fx-alerts-api-pruebas"

  val jwt: Rs256Jwt = Rs256Jwt(TestKeys.primary, issuer, audience)

  /** Firmado con otra clave: la verificación debe rechazarlo por firma inválida. */
  val foreignJwt: Rs256Jwt = Rs256Jwt(TestKeys.other, issuer, audience)

  val tokens: JwtTokens[IO] = JwtTokens[IO](jwt)

  val auth: BearerAuthentication[IO] = BearerAuthentication[IO](tokens)

  def claims(
      clientId: String,
      scopes: Set[Scope],
      issuedAt: Instant = Instant.now(),
      ttl: FiniteDuration = 5.minutes
  ): AccessTokenClaims =
    AccessTokenClaims(
      issuer = issuer,
      subject = ClientId.from(clientId).fold(error => throw new AssertionError(error), identity),
      audience = audience,
      issuedAt = issuedAt,
      expiresAt = issuedAt.plusSeconds(ttl.toSeconds),
      tokenId = UUID.randomUUID(),
      scopes = scopes
    )

  /** Token válido para el cliente con los alcances indicados (todos si no se indica ninguno). */
  def bearer(clientId: String, scopes: Scope*): String =
    jwt.sign(claims(clientId, if scopes.isEmpty then Scope.values.toSet else scopes.toSet))

  def expired(clientId: String): String =
    jwt.sign(claims(clientId, Scope.values.toSet, issuedAt = Instant.now().minusSeconds(3600)))

  def foreign(clientId: String): String =
    foreignJwt.sign(claims(clientId, Scope.values.toSet))

  extension (request: Request[IO])
    def withBearer(token: String): Request[IO] =
      request.putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, token)))

    def authenticatedAs(clientId: String, scopes: Scope*): Request[IO] =
      request.withBearer(bearer(clientId, scopes*))
