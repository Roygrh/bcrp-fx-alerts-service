package pe.quiroz.fxalerts.infrastructure.http.auth

import pe.quiroz.fxalerts.application.security.{AuthenticatedClient, Scope}
import pe.quiroz.fxalerts.infrastructure.http.problem.{ApiError, ProblemDetails}
import sttp.model.headers.WWWAuthenticateChallenge
import sttp.tapir.*
import sttp.tapir.EndpointInput.AuthType
import sttp.tapir.server.ServerEndpoint

import scala.collection.immutable.ListMap

/**
 * Esquema de seguridad de la API, declarado como entrada de Tapir para que forme parte del
 * contrato: el documento OpenAPI publica el flujo `clientCredentials` con su `tokenUrl`, el
 * catálogo de alcances y el alcance que exige cada endpoint, y Swagger UI permite obtener un token
 * desde el propio botón "Authorize".
 *
 * El token se declara opcional en la entrada (`Option[String]`): así su ausencia no es un fallo de
 * decodificación genérico sino un caso que la lógica de seguridad responde con el 401 y el reto
 * `WWW-Authenticate` de este servicio. Solo una cabecera `Authorization` con otro esquema llega al
 * manejador de fallos de decodificación, que también responde 401.
 */
object ApiSecurity:

  /** Valor de `realm` en los retos `WWW-Authenticate`. */
  val realm: String = "bcrp-fx-alerts"

  /** Ruta del endpoint de emisión de tokens, publicada como `tokenUrl` en el esquema OAuth2. */
  val tokenPath: String = "/oauth/token"

  /** Nombre del esquema en `components.securitySchemes`. */
  val schemeName: String = "oauth2"

  val challenge: WWWAuthenticateChallenge = WWWAuthenticateChallenge.bearer(realm)

  /** Reto para un token presente pero inválido (RFC 6750 §3.1, `invalid_token`). */
  val invalidTokenChallenge: WWWAuthenticateChallenge =
    challenge.addParam("error", "invalid_token")

  /** Reto para un token válido sin el alcance necesario (RFC 6750 §3.1, `insufficient_scope`). */
  def insufficientScopeChallenge(scope: Scope): WWWAuthenticateChallenge =
    challenge.addParam("error", "insufficient_scope").addParam("scope", scope.value)

  def unauthorized(challenge: WWWAuthenticateChallenge): ApiError =
    ApiError.Unauthorized(
      ProblemDetails.unauthorized,
      challenge.toString
    )

  def forbidden(scope: Scope): ApiError =
    ApiError.Forbidden(
      ProblemDetails.forbidden(scope.value),
      insufficientScopeChallenge(scope).toString
    )

  private val scopeCatalogue: ListMap[String, String] =
    ListMap(Scope.values.toList.map(scope => scope.value -> scope.description)*)

  /** Entrada de seguridad que exige un token `Bearer` con el alcance indicado. */
  def bearer(scope: Scope): EndpointInput.Auth[Option[String], AuthType.ScopedOAuth2] =
    auth.oauth2
      .clientCredentialsFlowOptional(tokenPath, None, scopeCatalogue, challenge)
      .securitySchemeName(schemeName)
      .description(
        "Token de acceso JWT (RS256) obtenido en `POST /oauth/token` con el flujo " +
          "`client_credentials`; se envía como `Authorization: Bearer <token>`"
      )
      .requiredScopes(Seq(scope.value))

/**
 * Endpoint protegido: la definición Tapir con su entrada de seguridad más el alcance que exige,
 * juntos para que la documentación y la comprobación en tiempo de ejecución no puedan divergir.
 */
final class SecuredEndpoint[I, O](
    val scope: Scope,
    val endpoint: Endpoint[Option[String], I, ApiError, O, Any]
):

  /** Enlaza la lógica de servidor, que recibe la identidad autenticada y la entrada. */
  def serverLogic[F[_]](auth: BearerAuthentication[F])(
      logic: AuthenticatedClient => I => F[Either[ApiError, O]]
  ): ServerEndpoint[Any, F] =
    endpoint.serverSecurityLogic(auth.requiring(scope)).serverLogic(logic)

object SecuredEndpoint:

  def apply[I, O](scope: Scope)(
      endpoint: Endpoint[Unit, I, ApiError, O, Any]
  ): SecuredEndpoint[I, O] =
    new SecuredEndpoint(scope, endpoint.securityIn(ApiSecurity.bearer(scope)))
