package pe.quiroz.fxalerts.infrastructure.http.auth

import cats.Monad
import cats.syntax.all.*
import org.http4s.headers.Authorization
import org.http4s.{AuthScheme, Credentials, Request}
import org.typelevel.log4cats.Logger
import pe.quiroz.fxalerts.application.security.{AuthenticatedClient, Scope, TokenVerifier}
import pe.quiroz.fxalerts.infrastructure.http.problem.ApiError

/**
 * Lógica de seguridad de los endpoints protegidos: valida el token `Bearer` y comprueba el alcance.
 *
 * Los tres desenlaces negativos se responden en formato Problem Details:
 *   - sin token → 401 con `WWW-Authenticate: Bearer realm=...`;
 *   - token inválido por cualquier motivo → 401 con `error="invalid_token"`, cuerpo idéntico al
 *     anterior; el motivo concreto solo va al log;
 *   - token válido sin el alcance exigido → 403 con `error="insufficient_scope"`.
 *
 * Nunca se registra el token ni la cabecera: solo el motivo del rechazo y, en el 403, el cliente.
 */
final class BearerAuthentication[F[_]: Monad: Logger](verifier: TokenVerifier[F]):

  def requiring(scope: Scope)(token: Option[String]): F[Either[ApiError, AuthenticatedClient]] =
    token match
      case None =>
        ApiSecurity.unauthorized(ApiSecurity.challenge).asLeft[AuthenticatedClient].pure[F]
      case Some(raw) =>
        verifier.verify(raw).flatMap {
          case Left(rejection) =>
            Logger[F]
              .info(s"Token rechazado motivo=$rejection")
              .as(ApiSecurity.unauthorized(ApiSecurity.invalidTokenChallenge).asLeft)
          case Right(client) if client.has(scope) =>
            client.asRight[ApiError].pure[F]
          case Right(client) =>
            Logger[F]
              .info(
                s"Alcance insuficiente client=${client.clientId.value} requerido=${scope.value}"
              )
              .as(ApiSecurity.forbidden(scope).asLeft)
        }

  /**
   * Identificador del cliente que presenta un token válido en la petición, para el log de
   * peticiones. Es una segunda verificación independiente de la del endpoint (barata: una firma
   * RSA) y no influye en la respuesta.
   */
  def subjectOf(request: Request[F]): F[Option[String]] =
    request.headers.get[Authorization].map(_.credentials) match
      case Some(Credentials.Token(AuthScheme.Bearer, token)) =>
        verifier.verify(token).map(_.toOption.map(_.clientId.value))
      case _ => none[String].pure[F]
