package pe.quiroz.fxalerts.infrastructure.security.jwt

import io.circe.parser.parse
import munit.FunSuite
import pe.quiroz.fxalerts.application.security.{AccessTokenClaims, Scope, TokenRejection}
import pe.quiroz.fxalerts.domain.alert.ClientId
import pe.quiroz.fxalerts.infrastructure.security.TestKeys

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.util.{Base64, UUID}
import scala.concurrent.duration.*

/** Firma y verificación puras: el instante "actual" se pasa explícitamente. */
class Rs256JwtSuite extends FunSuite:

  private val issuer   = "emisor-pruebas"
  private val audience = "api-pruebas"
  private val now      = Instant.parse("2026-08-30T12:00:00Z")

  private val jwt     = Rs256Jwt(TestKeys.primary, issuer, audience)
  private val foreign = Rs256Jwt(TestKeys.other, issuer, audience)

  private val clientId = ClientId.from("cliente-001").toOption.get

  private def claims(
      issuedAt: Instant = now,
      ttl: FiniteDuration = 15.minutes,
      issuer: String = issuer,
      audience: String = audience,
      scopes: Set[Scope] = Set(Scope.AlertsRead, Scope.RatesRead)
  ): AccessTokenClaims =
    AccessTokenClaims(
      issuer = issuer,
      subject = clientId,
      audience = audience,
      issuedAt = issuedAt,
      expiresAt = issuedAt.plusSeconds(ttl.toSeconds),
      tokenId = UUID.fromString("6f1c2a3e-9d4b-4c1a-8e2f-0b7d5a6c4e21"),
      scopes = scopes
    )

  private val decoder = Base64.getUrlDecoder
  private val encoder = Base64.getUrlEncoder.withoutPadding

  private def segment(token: String, index: Int): String =
    new String(decoder.decode(token.split('.')(index)), UTF_8)

  test("firma un token compacto con cabecera RS256/at+jwt y los claims esperados"):
    val token   = jwt.sign(claims())
    val parts   = token.split('.')
    val header  = parse(segment(token, 0)).toOption.get
    val payload = parse(segment(token, 1)).toOption.get
    assertEquals(parts.length, 3)
    assert(!token.contains("="))
    assertEquals(header.hcursor.get[String]("alg").toOption, Some("RS256"))
    assertEquals(header.hcursor.get[String]("typ").toOption, Some("at+jwt"))
    assertEquals(payload.hcursor.get[String]("iss").toOption, Some(issuer))
    assertEquals(payload.hcursor.get[String]("sub").toOption, Some("cliente-001"))
    assertEquals(payload.hcursor.get[String]("client_id").toOption, Some("cliente-001"))
    assertEquals(payload.hcursor.get[String]("aud").toOption, Some(audience))
    assertEquals(payload.hcursor.get[Long]("iat").toOption, Some(now.getEpochSecond))
    assertEquals(payload.hcursor.get[Long]("exp").toOption, Some(now.getEpochSecond + 900))
    assertEquals(
      payload.hcursor.get[String]("jti").toOption,
      Some("6f1c2a3e-9d4b-4c1a-8e2f-0b7d5a6c4e21")
    )
    assertEquals(payload.hcursor.get[String]("scope").toOption, Some("alerts:read rates:read"))

  test("verifica un token válido y devuelve sujeto, alcances e identificador"):
    val result = jwt.verify(jwt.sign(claims()), now.plusSeconds(60))
    val client = result.getOrElse(fail(s"Se esperaba un token válido: $result"))
    assertEquals(client.clientId, clientId)
    assertEquals(client.scopes, Set(Scope.AlertsRead, Scope.RatesRead))
    assertEquals(client.tokenId.toString, "6f1c2a3e-9d4b-4c1a-8e2f-0b7d5a6c4e21")

  test("rechaza un token caducado y admite el desfase de reloj tolerado"):
    val token = jwt.sign(claims(ttl = 1.minute))
    assertEquals(jwt.verify(token, now.plusSeconds(61 + 30)), Left(TokenRejection.Expired))
    assert(jwt.verify(token, now.plusSeconds(61 + 10)).isRight)

  test("rechaza un token emitido en el futuro más allá de la tolerancia"):
    val token = jwt.sign(claims(issuedAt = now.plusSeconds(120)))
    assertEquals(jwt.verify(token, now), Left(TokenRejection.NotYetValid))

  test("rechaza un token firmado con otra clave"):
    assertEquals(
      jwt.verify(foreign.sign(claims()), now),
      Left(TokenRejection.InvalidSignature)
    )

  test("rechaza un token cuyo contenido se altera tras la firma"):
    val token   = jwt.sign(claims())
    val parts   = token.split('.')
    val altered = segment(token, 1).replace("cliente-001", "cliente-002")
    val forged  = s"${parts(0)}.${encoder.encodeToString(altered.getBytes(UTF_8))}.${parts(2)}"
    assertEquals(jwt.verify(forged, now), Left(TokenRejection.InvalidSignature))

  test("rechaza un emisor o una audiencia distintos de los configurados"):
    val otherIssuer   = Rs256Jwt(TestKeys.primary, "otro-emisor", audience)
    val otherAudience = Rs256Jwt(TestKeys.primary, issuer, "otra-api")
    assertEquals(
      jwt.verify(otherIssuer.sign(claims(issuer = "otro-emisor")), now),
      Left(TokenRejection.WrongIssuer)
    )
    assertEquals(
      jwt.verify(otherAudience.sign(claims(audience = "otra-api")), now),
      Left(TokenRejection.WrongAudience)
    )

  test("rechaza tokens mal formados"):
    List("", "abc", "a.b", "a.b.c", "..", "no.es.base64!").foreach { token =>
      val result = jwt.verify(token, now)
      assert(result.isLeft, s"'$token' debería rechazarse")
    }
    assertEquals(jwt.verify("a.b.c", now), Left(TokenRejection.Malformed))

  test("rechaza un token cuya firma se altera"):
    val token = jwt.sign(claims())
    val parts = token.split('.')
    // Se altera el primer carácter de la firma (sus seis bits altos), de modo que el valor
    // decodificado cambia con certeza; alterar el último podría no cambiar ningún byte.
    val flipped = if parts(2).head == 'A' then 'B' else 'A'
    val forged  = s"${parts(0)}.${parts(1)}.$flipped${parts(2).tail}"
    assertEquals(jwt.verify(forged, now), Left(TokenRejection.InvalidSignature))

  test("rechaza un token con alg=none aunque el contenido sea plausible"):
    val header  = encoder.encodeToString("""{"alg":"none","typ":"at+jwt"}""".getBytes(UTF_8))
    val payload = jwt.sign(claims()).split('.')(1)
    assertEquals(jwt.verify(s"$header.$payload.", now), Left(TokenRejection.Malformed))
    assertEquals(jwt.verify(s"$header.$payload.x", now), Left(TokenRejection.Malformed))

  test("rechaza un token con otro tipo (typ) aunque la firma fuera válida"):
    val other = encoder.encodeToString("""{"alg":"RS256","typ":"JWT"}""".getBytes(UTF_8))
    val token = jwt.sign(claims())
    val parts = token.split('.')
    assertEquals(jwt.verify(s"$other.${parts(1)}.${parts(2)}", now), Left(TokenRejection.Malformed))
