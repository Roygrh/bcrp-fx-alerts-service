package pe.quiroz.fxalerts.infrastructure.http.middleware

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.implicits.*
import org.http4s.{Header, HttpApp, Request, Response, Status}
import org.typelevel.ci.*
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.util.UUID

class RequestLoggingSuite extends CatsEffectSuite:

  private given Logger[IO] = Slf4jLogger.getLogger[IO]

  /** Doble que devuelve en `X-Echo` el identificador que vio en la petición. */
  private val app: HttpApp[IO] = RequestLogging(
    HttpApp[IO] { request =>
      val seen = request.headers
        .get(RequestLogging.requestIdHeader)
        .map(_.head.value)
        .getOrElse("ausente")
      IO.pure(Response[IO](Status.Ok).putHeaders(Header.Raw(ci"X-Echo", seen)))
    }
  )

  private def requestIdOf(response: Response[IO]): Option[String] =
    response.headers.get(RequestLogging.requestIdHeader).map(_.head.value)

  private def echoOf(response: Response[IO]): Option[String] =
    response.headers.get(ci"X-Echo").map(_.head.value)

  test("genera un X-Request-Id (UUID), lo inyecta en la petición y lo devuelve"):
    app.run(Request[IO](uri = uri"/health")).map { response =>
      val generated = requestIdOf(response).getOrElse(fail("La respuesta no trae X-Request-Id"))
      assertEquals(UUID.fromString(generated).toString, generated)
      assertEquals(echoOf(response), Some(generated))
    }

  test("propaga el X-Request-Id recibido"):
    val request = Request[IO](uri = uri"/health")
      .putHeaders(Header.Raw(RequestLogging.requestIdHeader, "pedido-123"))
    app.run(request).map { response =>
      assertEquals(requestIdOf(response), Some("pedido-123"))
      assertEquals(echoOf(response), Some("pedido-123"))
    }

  test("descarta un identificador recibido con caracteres no admitidos y genera uno propio"):
    val request = Request[IO](uri = uri"/health")
      .putHeaders(Header.Raw(RequestLogging.requestIdHeader, "abc def%0aotra-linea"))
    app.run(request).map { response =>
      val assigned = requestIdOf(response).getOrElse(fail("La respuesta no trae X-Request-Id"))
      assertNotEquals(assigned, "abc def%0aotra-linea")
      assertEquals(UUID.fromString(assigned).toString, assigned)
    }
