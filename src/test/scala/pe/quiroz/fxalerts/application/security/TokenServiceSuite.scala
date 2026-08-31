package pe.quiroz.fxalerts.application.security

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import pe.quiroz.fxalerts.application.security.TokenRequestError.*
import pe.quiroz.fxalerts.domain.alert.ClientId

import scala.concurrent.duration.*

/**
 * Emisión de tokens sin criptografía real: el registro, el derivador de secretos y el firmante son
 * dobles, de modo que la suite prueba únicamente las reglas del flujo `client_credentials`.
 */
class TokenServiceSuite extends CatsEffectSuite:

  private given Logger[IO] = Slf4jLogger.getLogger[IO]

  private val policy = TokenPolicy("emisor-pruebas", "api-pruebas", 15.minutes)

  /** Doble del derivador: el "hash" es el secreto con un prefijo, suficiente para distinguirlos. */
  private val plainHasher: SecretHasher[IO] = new SecretHasher[IO]:
    def hash(secret: String): IO[SecretHash] =
      IO.fromEither(SecretHash.from(s"plain:$secret").leftMap(new IllegalArgumentException(_)))
    def verify(secret: String, hash: SecretHash): IO[Boolean] =
      IO.pure(hash.encoded == s"plain:$secret")

  private def client(id: String, secret: String, scopes: Scope*): RegisteredClient =
    RegisteredClient(
      ClientId.from(id).toOption.get,
      SecretHash.from(s"plain:$secret").toOption.get,
      scopes.toSet
    )

  private val registry: ClientRegistry[IO] =
    val clients = List(
      client("cliente-001", "secreto-001", Scope.AlertsRead, Scope.AlertsWrite),
      client("monitor-001", "secreto-monitor", Scope.RatesRead)
    ).map(c => c.id -> c).toMap
    id => IO.pure(clients.get(id))

  /** Doble del firmante: conserva los claims recibidos y devuelve un token reconocible. */
  private def withService[A](body: (TokenService[IO], Ref[IO, List[AccessTokenClaims]]) => IO[A]) =
    for
      signed <- Ref.of[IO, List[AccessTokenClaims]](Nil)
      issuer = new TokenIssuer[IO]:
        def sign(claims: AccessTokenClaims): IO[String] =
          signed.update(claims :: _).as(s"token-${claims.subject.value}")
      service <- TokenService[IO](registry, plainHasher, issuer, policy)
      result  <- body(service, signed)
    yield result

  private def request(
      clientId: String = "cliente-001",
      secret: String = "secreto-001",
      grantType: Option[String] = Some("client_credentials"),
      scope: Option[String] = None
  ): TokenRequest =
    TokenRequest(grantType, Some(ClientCredentials(clientId, secret)), scope)

  test("credenciales válidas sin scope: token con todos los alcances del cliente y los claims"):
    withService { (service, signed) =>
      for
        result <- service.issue(request())
        claims <- signed.get
      yield
        val issued = result.getOrElse(fail(s"Se esperaba un token: $result"))
        assertEquals(issued.accessToken, "token-cliente-001")
        assertEquals(issued.expiresIn, 15.minutes)
        assertEquals(issued.scopes, Set(Scope.AlertsRead, Scope.AlertsWrite))
        val signedClaims = claims.headOption.getOrElse(fail("No se firmó ningún token"))
        assertEquals(signedClaims.issuer, "emisor-pruebas")
        assertEquals(signedClaims.audience, "api-pruebas")
        assertEquals(signedClaims.subject.value, "cliente-001")
        assertEquals(signedClaims.expiresAt, signedClaims.issuedAt.plusSeconds(900))
        assertEquals(signedClaims.scopes, Set(Scope.AlertsRead, Scope.AlertsWrite))
    }

  test("scope solicitado: el token lleva solo el subconjunto pedido"):
    withService { (service, _) =>
      service.issue(request(scope = Some("alerts:read"))).map { result =>
        assertEquals(result.map(_.scopes), Right(Set(Scope.AlertsRead)))
      }
    }

  test("cada token lleva un jti distinto"):
    withService { (service, signed) =>
      for
        _      <- service.issue(request())
        _      <- service.issue(request())
        claims <- signed.get
      yield
        assertEquals(claims.size, 2)
        assertNotEquals(claims.map(_.tokenId).distinct.size, 1)
    }

  test("cliente desconocido: invalid_client"):
    withService { (service, signed) =>
      for
        result <- service.issue(request(clientId = "nadie"))
        claims <- signed.get
      yield
        assertEquals(result, Left(InvalidClient))
        assertEquals(claims, Nil)
    }

  test("secreto incorrecto: invalid_client, indistinguible del cliente desconocido"):
    withService { (service, _) =>
      for
        wrongSecret <- service.issue(request(secret = "otro"))
        unknown     <- service.issue(request(clientId = "nadie"))
      yield
        assertEquals(wrongSecret, Left(InvalidClient))
        assertEquals(wrongSecret, unknown)
    }

  test("sin credenciales: invalid_client"):
    withService { (service, _) =>
      service
        .issue(TokenRequest(Some("client_credentials"), None, None))
        .map(result => assertEquals(result, Left(InvalidClient)))
    }

  test("grant_type no soportado: unsupported_grant_type, sin verificar credenciales"):
    withService { (service, _) =>
      service.issue(request(grantType = Some("password"))).map { result =>
        assertEquals(result, Left(UnsupportedGrantType("password")))
        assertEquals(result.left.map(_.code), Left("unsupported_grant_type"))
      }
    }

  test("grant_type ausente: invalid_request"):
    withService { (service, _) =>
      service.issue(request(grantType = None)).map { result =>
        assertEquals(result.left.map(_.code), Left("invalid_request"))
      }
    }

  test("scope no concedido al cliente: invalid_scope nombrando solo los rechazados"):
    withService { (service, _) =>
      service.issue(request(scope = Some("alerts:read rates:read"))).map { result =>
        assertEquals(result, Left(InvalidScope(List("rates:read"))))
      }
    }

  test("scope desconocido: invalid_scope"):
    withService { (service, _) =>
      service.issue(request(scope = Some("admin:all"))).map { result =>
        assertEquals(result, Left(InvalidScope(List("admin:all"))))
      }
    }

  test("error_description respeta el juego de caracteres de RFC 6749 aunque el scope no"):
    withService { (service, _) =>
      service.issue(request(scope = Some("ñandú\"\\x"))).map { result =>
        val description = result.left.map(_.description).left.getOrElse(fail("Se esperaba error"))
        assert(
          description.forall(c => c >= ' ' && c <= '~' && c != '"' && c != '\\'),
          description
        )
      }
    }

  test("un cliente solo puede pedir scopes dentro de los suyos aunque existan para otros"):
    withService { (service, _) =>
      service
        .issue(
          request(clientId = "monitor-001", secret = "secreto-monitor", scope = Some("alerts:read"))
        )
        .map(result => assertEquals(result, Left(InvalidScope(List("alerts:read")))))
    }
