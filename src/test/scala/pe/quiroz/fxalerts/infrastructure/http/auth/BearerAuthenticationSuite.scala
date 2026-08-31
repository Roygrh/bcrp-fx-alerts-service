package pe.quiroz.fxalerts.infrastructure.http.auth

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.implicits.*
import org.http4s.{Header, Request}
import org.typelevel.ci.*
import pe.quiroz.fxalerts.application.security.Scope
import pe.quiroz.fxalerts.infrastructure.http.auth.TestTokens.*
import pe.quiroz.fxalerts.infrastructure.http.problem.ApiError

/** Lógica de seguridad aislada de los endpoints: cada desenlace con su código y su reto. */
class BearerAuthenticationSuite extends CatsEffectSuite:

  private val requireRead = TestTokens.auth.requiring(Scope.AlertsRead)

  test("sin token: 401 con el reto Bearer del realm"):
    requireRead(None).map {
      case Left(ApiError.Unauthorized(problem, challenge)) =>
        assertEquals(challenge, "Bearer realm=\"bcrp-fx-alerts\"")
        assertEquals(problem.status, 401)
        assertEquals(problem.`type`, "urn:fx-alerts:problem:unauthorized")
      case other => fail(s"Se esperaba 401: $other")
    }

  test("token inválido por cualquier motivo: 401 invalid_token con el mismo cuerpo"):
    for
      missing <- requireRead(None)
      expired <- requireRead(Some(TestTokens.expired("cliente-001")))
      foreign <- requireRead(Some(TestTokens.foreign("cliente-001")))
      garbage <- requireRead(Some("no.es.un.token"))
    yield
      val missingProblem = missing.left.map(_.problem).left.getOrElse(fail("Se esperaba 401"))
      List(expired, foreign, garbage).foreach {
        case Left(ApiError.Unauthorized(problem, challenge)) =>
          assertEquals(challenge, "Bearer realm=\"bcrp-fx-alerts\", error=\"invalid_token\"")
          assertEquals(problem, missingProblem)
        case other => fail(s"Se esperaba 401: $other")
      }

  test("token válido sin el alcance: 403 insufficient_scope nombrando el alcance"):
    requireRead(Some(TestTokens.bearer("cliente-001", Scope.AlertsWrite))).map {
      case Left(ApiError.Forbidden(problem, challenge)) =>
        assertEquals(
          challenge,
          "Bearer realm=\"bcrp-fx-alerts\", error=\"insufficient_scope\", scope=\"alerts:read\""
        )
        assertEquals(problem.status, 403)
        assert(problem.detail.contains("alerts:read"))
      case other => fail(s"Se esperaba 403: $other")
    }

  test("token válido con el alcance: identidad autenticada"):
    requireRead(Some(TestTokens.bearer("cliente-001", Scope.AlertsRead))).map { result =>
      assertEquals(result.map(_.clientId.value), Right("cliente-001"))
      assertEquals(result.map(_.scopes), Right(Set(Scope.AlertsRead)))
    }

  test("subjectOf resuelve el cliente de un token válido y nada en cualquier otro caso"):
    val base = Request[IO](uri = uri"/api/v1/alerts")
    for
      valid   <- TestTokens.auth.subjectOf(base.authenticatedAs("cliente-007"))
      none    <- TestTokens.auth.subjectOf(base)
      invalid <- TestTokens.auth.subjectOf(base.withBearer(TestTokens.foreign("cliente-007")))
      basic   <- TestTokens.auth.subjectOf(
        base.putHeaders(Header.Raw(ci"Authorization", "Basic Y2xpZW50ZTpzZWNyZXRv"))
      )
    yield
      assertEquals(valid, Some("cliente-007"))
      assertEquals(none, None)
      assertEquals(invalid, None)
      assertEquals(basic, None)
