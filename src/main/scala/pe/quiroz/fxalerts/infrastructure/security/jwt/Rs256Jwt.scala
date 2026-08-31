package pe.quiroz.fxalerts.infrastructure.security.jwt

import io.circe.Codec
import io.circe.parser.decode
import io.circe.syntax.*
import pe.quiroz.fxalerts.application.security.{
  AccessTokenClaims,
  AuthenticatedClient,
  Scope,
  TokenRejection
}
import pe.quiroz.fxalerts.domain.alert.ClientId
import pe.quiroz.fxalerts.infrastructure.security.SigningKeys

import java.nio.charset.StandardCharsets.UTF_8
import java.security.Signature
import java.time.Instant
import java.util.{Base64, UUID}
import scala.concurrent.duration.*
import scala.util.Try

/** Cabecera JOSE (RFC 7515 §4) de los tokens que emite este servicio. */
private[jwt] final case class JwtHeader(alg: String, typ: String) derives Codec.AsObject

/**
 * Carga útil del token: claims registrados (RFC 7519 §4.1) más `scope` (RFC 8693 §4.2) y
 * `client_id` (RFC 9068 §2.2). Los instantes viajan en segundos desde la época (NumericDate).
 */
private[jwt] final case class JwtPayload(
    iss: String,
    sub: String,
    aud: String,
    iat: Long,
    exp: Long,
    jti: String,
    scope: String,
    client_id: String
) derives Codec.AsObject

/**
 * Firma y verificación de JWT con RS256 (RSASSA-PKCS1-v1_5 con SHA-256, RFC 7518 §3.3) sobre las
 * primitivas del JDK.
 *
 * Es una función pura de (token, instante): no tiene efectos y se prueba sin reloj real. Solo se
 * aceptan tokens con exactamente la cabecera que este servicio produce (`alg` RS256 y `typ`
 * `at+jwt`, el tipo de token de acceso de RFC 9068), por lo que un token con `alg: none` u otro
 * algoritmo se rechaza antes de mirar la firma y nunca hay ambigüedad sobre qué clave verifica.
 *
 * Todas las comprobaciones devuelven un [[TokenRejection]] distinto para el log y las pruebas; la
 * capa HTTP los responde todos igual.
 *
 * @param keys
 *   par RSA: la privada firma, la pública verifica
 * @param issuer
 *   valor exigido en `iss`
 * @param audience
 *   valor exigido en `aud`
 * @param leeway
 *   tolerancia de desfase de reloj al comparar `exp` e `iat` con el instante actual
 */
final class Rs256Jwt(
    keys: SigningKeys,
    issuer: String,
    audience: String,
    leeway: FiniteDuration = Rs256Jwt.defaultLeeway
):

  import Rs256Jwt.*

  def sign(claims: AccessTokenClaims): String =
    val payload = JwtPayload(
      iss = claims.issuer,
      sub = claims.subject.value,
      aud = claims.audience,
      iat = claims.issuedAt.getEpochSecond,
      exp = claims.expiresAt.getEpochSecond,
      jti = claims.tokenId.toString,
      scope = Scope.render(claims.scopes),
      client_id = claims.subject.value
    )
    val signingInput = s"${encode(header.asJson.noSpaces)}.${encode(payload.asJson.noSpaces)}"
    val signature    = Signature.getInstance(jdkAlgorithm)
    signature.initSign(keys.privateKey)
    signature.update(signingInput.getBytes(UTF_8))
    s"$signingInput.${encoder.encodeToString(signature.sign())}"

  def verify(token: String, now: Instant): Either[TokenRejection, AuthenticatedClient] =
    for
      parts   <- split(token)
      _       <- parseHeader(parts.header).filterOrElse(_ == header, TokenRejection.Malformed)
      _       <- checkSignature(parts)
      payload <- parsePayload(parts.payload)
      _       <- checkTimes(payload, now)
      _       <- Either.cond(payload.iss == issuer, (), TokenRejection.WrongIssuer)
      _       <- Either.cond(payload.aud == audience, (), TokenRejection.WrongAudience)
      subject <- ClientId.from(payload.sub).left.map(_ => TokenRejection.InvalidSubject)
      _       <- Either.cond(payload.client_id == payload.sub, (), TokenRejection.InvalidSubject)
      scopes  <- Scope.parseList(payload.scope).left.map(_ => TokenRejection.UnknownScope)
      tokenId <- Try(UUID.fromString(payload.jti)).toEither.left.map(_ => TokenRejection.Malformed)
    yield AuthenticatedClient(subject, scopes, tokenId)

  private def checkSignature(parts: Parts): Either[TokenRejection, Unit] =
    val verified = Try {
      val signature = Signature.getInstance(jdkAlgorithm)
      signature.initVerify(keys.publicKey)
      signature.update(s"${parts.header}.${parts.payload}".getBytes(UTF_8))
      signature.verify(decoder.decode(parts.signature))
    }.getOrElse(false)
    Either.cond(verified, (), TokenRejection.InvalidSignature)

  private def checkTimes(payload: JwtPayload, now: Instant): Either[TokenRejection, Unit] =
    val tolerance = leeway.toSeconds
    for
      _ <- Either.cond(payload.exp + tolerance > now.getEpochSecond, (), TokenRejection.Expired)
      _ <- Either.cond(
        payload.iat - tolerance <= now.getEpochSecond,
        (),
        TokenRejection.NotYetValid
      )
    yield ()

object Rs256Jwt:

  val defaultLeeway: FiniteDuration = 30.seconds

  /** Única cabecera admitida, tanto al firmar como al verificar. */
  private[jwt] val header: JwtHeader = JwtHeader(alg = "RS256", typ = "at+jwt")

  private val jdkAlgorithm = "SHA256withRSA"
  private val encoder      = Base64.getUrlEncoder.withoutPadding
  private val decoder      = Base64.getUrlDecoder

  private final case class Parts(header: String, payload: String, signature: String)

  private def encode(json: String): String = encoder.encodeToString(json.getBytes(UTF_8))

  private def split(token: String): Either[TokenRejection, Parts] =
    token.split('.') match
      case Array(header, payload, signature)
          if header.nonEmpty && payload.nonEmpty && signature.nonEmpty =>
        Right(Parts(header, payload, signature))
      case _ => Left(TokenRejection.Malformed)

  private def parseHeader(segment: String): Either[TokenRejection, JwtHeader] =
    decodeSegment[JwtHeader](segment)

  private def parsePayload(segment: String): Either[TokenRejection, JwtPayload] =
    decodeSegment[JwtPayload](segment)

  private def decodeSegment[A: io.circe.Decoder](segment: String): Either[TokenRejection, A] =
    Try(new String(decoder.decode(segment), UTF_8)).toEither
      .flatMap(decode[A])
      .left
      .map(_ => TokenRejection.Malformed)
