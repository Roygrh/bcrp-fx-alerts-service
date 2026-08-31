package pe.quiroz.fxalerts.infrastructure.http.auth

import sttp.model.{HeaderNames, StatusCode}
import sttp.tapir.*
import sttp.tapir.json.circe.*

/**
 * Definición declarativa de `POST /oauth/token`: emisión de tokens con el flujo
 * `client_credentials` de OAuth 2.0 (RFC 6749 §4.4).
 *
 * Es el único endpoint de la API que no habla Problem Details: la forma de sus respuestas de éxito
 * y de error la fija el estándar, y los clientes OAuth genéricos (Swagger UI incluido) la esperan
 * tal cual. Las respuestas llevan `Cache-Control: no-store` como exige RFC 6749 §5.1.
 */
object TokenEndpoints:

  val token: PublicEndpoint[(Option[String], TokenRequestForm), TokenApiError, TokenResponse, Any] =
    endpoint.post
      .in("oauth" / "token")
      .tag("Seguridad")
      .summary("Emite un token de acceso")
      .description(
        "Flujo `client_credentials` (RFC 6749 §4.4). Las credenciales del cliente se envían por " +
          "HTTP Basic (`Authorization: Basic base64(client_id:client_secret)`, el mecanismo " +
          "recomendado) o en el propio cuerpo (`client_id` y `client_secret`), nunca por ambos. " +
          "`scope` restringe los alcances del token a un subconjunto de los concedidos al " +
          "cliente; sin él se conceden todos. El token es un JWT firmado con RS256, válido " +
          "durante `expires_in` segundos, que se presenta como `Authorization: Bearer <token>`. " +
          "Los errores siguen RFC 6749 §5.2: `invalid_client` (401), `invalid_request`, " +
          "`unsupported_grant_type` e `invalid_scope` (400)."
      )
      .in(
        header[Option[String]](HeaderNames.Authorization)
          .description("Credenciales del cliente por HTTP Basic (alternativa al cuerpo)")
      )
      .in(
        formBody[TokenRequestForm]
          .description("Parámetros de la concesión")
          .example(TokenRequestForm.example)
      )
      .out(jsonBody[TokenResponse].example(TokenResponse.example))
      .out(header(HeaderNames.CacheControl, "no-store"))
      .out(header(HeaderNames.Pragma, "no-cache"))
      .errorOut(
        oneOf[TokenApiError](
          oneOfVariant(
            StatusCode.BadRequest,
            jsonBody[TokenErrorResponse]
              .description("Petición, tipo de concesión o alcances no admitidos")
              .example(TokenErrorResponse.exampleUnsupportedGrant)
              .map(TokenApiError.BadRequest.apply)(_.body)
          ),
          oneOfVariant(
            StatusCode.Unauthorized,
            header[String](HeaderNames.WwwAuthenticate)
              .example(TokenRoutes.clientChallenge)
              .and(
                jsonBody[TokenErrorResponse]
                  .description("Credenciales de cliente ausentes o incorrectas")
                  .example(TokenErrorResponse.exampleInvalidClient)
              )
              .map((challenge, body) => TokenApiError.Unauthorized(body, challenge))(error =>
                (error.challenge, error.body)
              )
          )
        )
      )
