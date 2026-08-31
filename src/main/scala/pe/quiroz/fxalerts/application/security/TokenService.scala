package pe.quiroz.fxalerts.application.security

import cats.Monad
import cats.data.EitherT
import cats.effect.Clock
import cats.effect.std.UUIDGen
import cats.syntax.all.*
import org.typelevel.log4cats.Logger
import pe.quiroz.fxalerts.application.security.TokenRequestError.*
import pe.quiroz.fxalerts.domain.alert.ClientId

import scala.concurrent.duration.FiniteDuration

/**
 * Parámetros de los tokens que emite este servicio.
 *
 * @param issuer
 *   valor del claim `iss`
 * @param audience
 *   valor del claim `aud`
 * @param ttl
 *   vida de cada token desde su emisión
 */
final case class TokenPolicy(issuer: String, audience: String, ttl: FiniteDuration)

/**
 * Servicio de aplicación del flujo `client_credentials` (RFC 6749 §4.4).
 *
 * Comprueba en este orden: tipo de concesión, credenciales del cliente y alcances. Un cliente
 * desconocido y un secreto incorrecto producen exactamente el mismo resultado y cuestan el mismo
 * tiempo: cuando el cliente no existe se verifica el secreto contra un hash señuelo con los mismos
 * parámetros, de modo que la duración de la respuesta no delate qué identificadores existen.
 *
 * Sin `scope` en la petición se conceden todos los alcances del cliente (RFC 6749 §3.3 permite un
 * valor por defecto definido por el servidor); con `scope`, todos los pedidos deben estar
 * concedidos o la petición se rechaza entera.
 *
 * El log registra el `client_id` y los alcances de cada emisión, y el `client_id` de cada intento
 * fallido; nunca secretos ni tokens.
 */
final class TokenService[F[_]: Monad: Clock: UUIDGen: Logger] private (
    registry: ClientRegistry[F],
    hasher: SecretHasher[F],
    issuer: TokenIssuer[F],
    policy: TokenPolicy,
    decoyHash: SecretHash
):

  def issue(request: TokenRequest): F[Either[TokenRequestError, IssuedToken]] =
    val result = for
      _           <- EitherT.fromEither[F](checkGrantType(request.grantType))
      credentials <- EitherT.fromOption[F](request.credentials, InvalidClient: TokenRequestError)
      client      <- EitherT(authenticate(credentials))
      scopes      <- EitherT.fromEither[F](resolveScopes(client, request.scope))
      token       <- EitherT.liftF(mint(client, scopes))
    yield token
    result.value.flatTap(audit(request))

  private def checkGrantType(grantType: Option[String]): Either[TokenRequestError, Unit] =
    grantType match
      case None                                 => Left(InvalidRequest("Falta el campo grant_type"))
      case Some(TokenService.clientCredentials) => Right(())
      case Some(other)                          => Left(UnsupportedGrantType(other))

  private def authenticate(
      credentials: ClientCredentials
  ): F[Either[TokenRequestError, RegisteredClient]] =
    for
      registered <- ClientId.from(credentials.clientId).toOption.flatTraverse(registry.find)
      matches    <- hasher.verify(credentials.secret, registered.fold(decoyHash)(_.secretHash))
    yield registered.filter(_ => matches).toRight(InvalidClient)

  private def resolveScopes(
      client: RegisteredClient,
      requested: Option[String]
  ): Either[TokenRequestError, Set[Scope]] =
    requested.map(_.trim).filter(_.nonEmpty) match
      case None      => Right(client.scopes)
      case Some(raw) =>
        Scope.parseList(raw).left.map(InvalidScope.apply).flatMap { scopes =>
          val notGranted = scopes.diff(client.scopes)
          Either.cond(
            notGranted.isEmpty,
            scopes,
            InvalidScope(Scope.values.filter(notGranted.contains).map(_.value).toList)
          )
        }

  private def mint(client: RegisteredClient, scopes: Set[Scope]): F[IssuedToken] =
    for
      now     <- Clock[F].realTimeInstant
      tokenId <- UUIDGen[F].randomUUID
      claims = AccessTokenClaims(
        issuer = policy.issuer,
        subject = client.id,
        audience = policy.audience,
        issuedAt = now,
        expiresAt = now.plusMillis(policy.ttl.toMillis),
        tokenId = tokenId,
        scopes = scopes
      )
      token <- issuer.sign(claims)
    yield IssuedToken(token, policy.ttl, scopes)

  private def audit(request: TokenRequest)(
      result: Either[TokenRequestError, IssuedToken]
  ): F[Unit] =
    val client = request.credentials.map(c => TokenService.loggable(c.clientId)).getOrElse("-")
    result match
      case Right(token) =>
        Logger[F].info(s"Token emitido client=$client scopes=${Scope.render(token.scopes)}")
      case Left(error) =>
        Logger[F].warn(s"Solicitud de token rechazada client=$client error=${error.code}")

object TokenService:

  val clientCredentials: String = "client_credentials"

  private val safeClientId = "^[A-Za-z0-9._-]{1,64}$".r

  /**
   * Construye el servicio calculando el hash señuelo con el mismo adaptador que verificará los
   * secretos reales, para que ambas verificaciones cuesten lo mismo.
   */
  def apply[F[_]: Monad: Clock: UUIDGen: Logger](
      registry: ClientRegistry[F],
      hasher: SecretHasher[F],
      issuer: TokenIssuer[F],
      policy: TokenPolicy
  ): F[TokenService[F]] =
    for
      random <- UUIDGen[F].randomUUID
      decoy  <- hasher.hash(s"decoy-$random")
    yield new TokenService[F](registry, hasher, issuer, policy, decoy)

  /** El `client_id` de una petición lo controla quien la envía: solo llega al log si es inocuo. */
  private def loggable(clientId: String): String =
    if safeClientId.matches(clientId) then clientId else "<no-valido>"
