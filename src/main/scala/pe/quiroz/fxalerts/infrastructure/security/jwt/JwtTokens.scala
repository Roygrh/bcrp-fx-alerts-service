package pe.quiroz.fxalerts.infrastructure.security.jwt

import cats.effect.{Clock, Sync}
import cats.syntax.all.*
import pe.quiroz.fxalerts.application.security.{
  AccessTokenClaims,
  AuthenticatedClient,
  TokenIssuer,
  TokenRejection,
  TokenVerifier
}

/**
 * Adaptador de [[TokenIssuer]] y [[TokenVerifier]] sobre [[Rs256Jwt]]: aporta el efecto y el reloj,
 * y deja la lógica en la función pura.
 */
final class JwtTokens[F[_]: Sync](jwt: Rs256Jwt) extends TokenIssuer[F] with TokenVerifier[F]:

  def sign(claims: AccessTokenClaims): F[String] =
    Sync[F].delay(jwt.sign(claims))

  def verify(token: String): F[Either[TokenRejection, AuthenticatedClient]] =
    Clock[F].realTimeInstant.map(jwt.verify(token, _))
