package pe.quiroz.fxalerts.infrastructure.http.auth

import io.circe.Codec as JsonCodec
import pe.quiroz.fxalerts.application.security.{IssuedToken, Scope, TokenRequestError}
import sttp.tapir.{Codec, CodecFormat, Schema}

/**
 * Cuerpo `application/x-www-form-urlencoded` de `POST /oauth/token` (RFC 6749 §4.4.2 y §2.3.1).
 *
 * Todos los campos son opcionales en la decodificación a propósito: la RFC define su propia forma
 * de error (`invalid_request`, `invalid_client`...) y es la lógica del endpoint, no el
 * decodificador, quien la produce. Los nombres de campo son los del estándar.
 *
 * @param grant_type
 *   único valor admitido: `client_credentials`
 * @param client_id
 *   identificador del cliente, si no se envía por HTTP Basic
 * @param client_secret
 *   secreto del cliente, si no se envía por HTTP Basic
 * @param scope
 *   alcances solicitados separados por espacio; sin él se conceden todos los del cliente
 */
final case class TokenRequestForm(
    grant_type: Option[String],
    client_id: Option[String],
    client_secret: Option[String],
    scope: Option[String]
) derives Schema

object TokenRequestForm:

  given Codec[String, TokenRequestForm, CodecFormat.XWwwFormUrlencoded] =
    Codec.formMapUtf8
      .map(fields =>
        TokenRequestForm(
          grant_type = fields.get("grant_type"),
          client_id = fields.get("client_id"),
          client_secret = fields.get("client_secret"),
          scope = fields.get("scope")
        )
      )(form =>
        List(
          "grant_type"    -> form.grant_type,
          "client_id"     -> form.client_id,
          "client_secret" -> form.client_secret,
          "scope"         -> form.scope
        ).collect { case (name, Some(value)) => name -> value }.toMap
      )
      .schema(summon[Schema[TokenRequestForm]])

  val example: TokenRequestForm = TokenRequestForm(
    grant_type = Some("client_credentials"),
    client_id = Some("cliente-001"),
    client_secret = Some("<secreto entregado al cliente>"),
    scope = Some("alerts:read alerts:write")
  )

/** Respuesta correcta de `POST /oauth/token` (RFC 6749 §5.1). */
final case class TokenResponse(
    access_token: String,
    token_type: String,
    expires_in: Long,
    scope: String
) derives JsonCodec.AsObject,
      Schema

object TokenResponse:

  val bearer: String = "Bearer"

  def from(issued: IssuedToken): TokenResponse =
    TokenResponse(
      access_token = issued.accessToken,
      token_type = bearer,
      expires_in = issued.expiresIn.toSeconds,
      scope = Scope.render(issued.scopes)
    )

  val example: TokenResponse = TokenResponse(
    access_token =
      "eyJhbGciOiJSUzI1NiIsInR5cCI6ImF0K2p3dCJ9.eyJpc3MiOiJiY3JwLWZ4LWFsZXJ0cy1zZXJ2aWNlIn0.c2lnbmF0dXJl",
    token_type = bearer,
    expires_in = 900,
    scope = "alerts:read alerts:write"
  )

/** Respuesta de error de `POST /oauth/token` (RFC 6749 §5.2). */
final case class TokenErrorResponse(error: String, error_description: String)
    derives JsonCodec.AsObject,
      Schema

object TokenErrorResponse:

  def from(error: TokenRequestError): TokenErrorResponse =
    TokenErrorResponse(error.code, error.description)

  val exampleInvalidClient: TokenErrorResponse = from(TokenRequestError.InvalidClient)

  val exampleUnsupportedGrant: TokenErrorResponse =
    from(TokenRequestError.UnsupportedGrantType("password"))

/**
 * Errores del endpoint de token con su código de estado: `invalid_client` responde 401 con el reto
 * `WWW-Authenticate` del esquema de autenticación de cliente que se admite (`Basic`); el resto
 * responde 400 (RFC 6749 §5.2).
 */
sealed trait TokenApiError extends Product with Serializable:
  def body: TokenErrorResponse

object TokenApiError:

  final case class BadRequest(body: TokenErrorResponse) extends TokenApiError

  final case class Unauthorized(body: TokenErrorResponse, challenge: String) extends TokenApiError

  def from(error: TokenRequestError): TokenApiError =
    error match
      case TokenRequestError.InvalidClient =>
        Unauthorized(TokenErrorResponse.from(error), TokenRoutes.clientChallenge)
      case _: TokenRequestError.InvalidRequest | _: TokenRequestError.UnsupportedGrantType |
          _: TokenRequestError.InvalidScope =>
        BadRequest(TokenErrorResponse.from(error))
