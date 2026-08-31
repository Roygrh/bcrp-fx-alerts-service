package pe.quiroz.fxalerts.infrastructure.http.auth

import cats.Monad
import cats.syntax.all.*
import pe.quiroz.fxalerts.application.security.TokenRequestError.{InvalidClient, InvalidRequest}
import pe.quiroz.fxalerts.application.security.{
  ClientCredentials,
  TokenRequest,
  TokenRequestError,
  TokenService
}
import sttp.model.headers.WWWAuthenticateChallenge
import sttp.tapir.server.ServerEndpoint

import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64
import scala.util.Try

/**
 * Enlaza el endpoint de token con el servicio de aplicación.
 *
 * Su única lógica propia es la mecánica HTTP de las credenciales (RFC 6749 §2.3.1): extraerlas de
 * la cabecera `Authorization: Basic` o del cuerpo, y rechazar con `invalid_request` que lleguen por
 * los dos mecanismos a la vez. Una cabecera con otro esquema, o un `Basic` que no se puede
 * interpretar, es `invalid_client` ("método de autenticación no admitido").
 */
final class TokenRoutes[F[_]: Monad](service: TokenService[F]):

  val serverEndpoints: List[ServerEndpoint[Any, F]] = List(
    TokenEndpoints.token.serverLogic { (authorization, form) =>
      TokenRoutes.credentials(authorization, form) match
        case Left(error) =>
          TokenApiError.from(error).asLeft[TokenResponse].pure[F]
        case Right(credentials) =>
          service
            .issue(TokenRequest(form.grant_type, credentials, form.scope))
            .map(_.bimap(TokenApiError.from, TokenResponse.from))
    }
  )

object TokenRoutes:

  /** Reto devuelto con `invalid_client`: el esquema de autenticación de cliente que se admite. */
  val clientChallenge: String = WWWAuthenticateChallenge.basic(ApiSecurity.realm).toString

  private val basicPrefix = "basic "

  /**
   * Resuelve las credenciales según RFC 6749 §2.3.1. Con `Basic`, un `client_id` en el cuerpo se
   * tolera solo si coincide con el usuario de la cabecera (identifica, no autentica); un
   * `client_secret` en el cuerpo junto a `Basic` es un segundo mecanismo y se rechaza.
   */
  private[auth] def credentials(
      authorization: Option[String],
      form: TokenRequestForm
  ): Either[TokenRequestError, Option[ClientCredentials]] =
    authorization match
      case Some(header) =>
        for
          basic <- parseBasic(header)
          _     <- Either.cond(
            form.client_secret.isEmpty,
            (),
            InvalidRequest("Las credenciales deben enviarse por un solo mecanismo: Basic o cuerpo")
          )
          _ <- Either.cond(
            form.client_id.forall(_ == basic.clientId),
            (),
            InvalidRequest("El client_id del cuerpo no coincide con el de la cabecera Basic")
          )
        yield Some(basic)
      case None =>
        (form.client_id, form.client_secret) match
          case (Some(id), Some(secret)) => Right(Some(ClientCredentials(id, secret)))
          case (None, None)             => Right(None)
          case _ => Left(InvalidRequest("client_id y client_secret deben enviarse juntos"))

  /**
   * `Basic base64(client_id:client_secret)`, con ambos valores codificados como
   * `application/x-www-form-urlencoded` antes del base64 (RFC 6749 §2.3.1).
   */
  private def parseBasic(header: String): Either[TokenRequestError, ClientCredentials] =
    val trimmed = header.trim
    if !trimmed.toLowerCase.startsWith(basicPrefix) then Left(InvalidClient)
    else
      Try(
        new String(Base64.getDecoder.decode(trimmed.drop(basicPrefix.length).trim), UTF_8)
      ).toOption
        .flatMap { decoded =>
          decoded.indexOf(':') match
            case -1 => None
            case at =>
              Try(
                ClientCredentials(
                  URLDecoder.decode(decoded.substring(0, at), UTF_8),
                  URLDecoder.decode(decoded.substring(at + 1), UTF_8)
                )
              ).toOption
        }
        .filter(_.clientId.nonEmpty)
        .toRight(InvalidClient)
