package pe.quiroz.fxalerts.infrastructure.bcrp

import cats.effect.testkit.TestControl
import cats.effect.{IO, Ref}
import munit.CatsEffectSuite
import org.http4s.client.Client
import org.http4s.implicits.*
import org.http4s.{HttpApp, Request, Response, Status}
import org.typelevel.ci.*
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import pe.quiroz.fxalerts.application.rate.Freshness
import pe.quiroz.fxalerts.domain.DomainError.{ExchangeRateNotPublished, ExchangeRateUnavailable}
import pe.quiroz.fxalerts.domain.alert.BcrpSeries
import pe.quiroz.fxalerts.domain.rate.RateProvider
import pe.quiroz.fxalerts.infrastructure.config.{BcrpConfig, RemoteCallConfig}

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import scala.concurrent.duration.*
import scala.io.Source

/**
 * Comportamiento del cliente BCRP sin red: el `Client` de http4s se monta sobre una `HttpApp`
 * guionizada y el tiempo (esperas entre reintentos, tiempos límite) es virtual gracias a
 * `TestControl`, de modo que las pruebas son deterministas e instantáneas.
 */
class BcrpExchangeRateClientSuite extends CatsEffectSuite:

  private given Logger[IO] = Slf4jLogger.getLogger[IO]

  private val series = BcrpSeries.UsdPenSbsSell

  private val config = BcrpConfig(
    baseUri = uri"http://bcrp.test/estadisticas/series/api",
    lookbackDays = 7,
    call = RemoteCallConfig(
      connectTimeout = 1.second,
      readTimeout = 2.seconds,
      maxRetries = 2,
      retryBackoff = 500.millis
    )
  )

  private val sampleBody: String =
    val stream = getClass.getResourceAsStream("/bcrp/PD04640PD-2026-08-20_2026-08-30.json")
    try Source.fromInputStream(stream, "UTF-8").mkString
    finally stream.close()

  private val allNotAvailable =
    """{"periods":[{"name":"29.Ago.26","values":["n.d."]},{"name":"30.Ago.26","values":["n.d."]}]}"""

  private val challengePage =
    "<!DOCTYPE html><html><head><script src=\"/_Incapsula_Resource\" async></script></head><body></body></html>"

  private def ok(body: String): IO[Response[IO]] = IO.pure(Response[IO](Status.Ok).withEntity(body))

  private def status(status: Status): IO[Response[IO]] = IO.pure(Response[IO](status))

  /**
   * Ejecuta `body` con un cliente que responde según el número de llamada (1-based) y con el
   * registro de las peticiones recibidas, todo bajo reloj virtual.
   */
  private def scripted[A](respond: Int => IO[Response[IO]])(
      body: (Client[IO], Ref[IO, List[Request[IO]]]) => IO[A]
  ): IO[A] =
    TestControl.executeEmbed {
      Ref.of[IO, List[Request[IO]]](Nil).flatMap { calls =>
        val app = HttpApp[IO] { request =>
          calls.updateAndGet(_ :+ request).flatMap(all => respond(all.size))
        }
        body(Client.fromHttpApp(app), calls)
      }
    }

  private def clientFor(client: Client[IO], config: BcrpConfig = config) =
    BcrpExchangeRateClient[IO](client, config)

  test("camino feliz: devuelve el último dato publicado, marcado FRESH, en una sola llamada"):
    scripted(_ => ok(sampleBody)) { (client, calls) =>
      for
        result   <- clientFor(client).latest(series)
        requests <- calls.get
      yield
        assertEquals(requests.size, 1)
        val snapshot = result.fold(e => fail(s"Se esperaba un dato: $e"), identity)
        assertEquals(snapshot.rate.series, series)
        assertEquals(snapshot.rate.date, LocalDate.of(2026, 8, 28))
        assertEquals(snapshot.rate.value, BigDecimal("3.523"))
        assertEquals(snapshot.freshness, Freshness.Fresh)
        assertEquals(snapshot.rate.provider, RateProvider.Bcrp)
        assert(snapshot.rate.official)
    }

  test("la petición sigue la estructura de la API con una ventana acotada y cabeceras explícitas"):
    scripted(_ => ok(sampleBody)) { (client, calls) =>
      for
        _        <- clientFor(client).latest(series)
        requests <- calls.get
      yield
        val request  = requests.head
        val segments = request.uri.path.segments.map(_.decoded()).toList
        assertEquals(segments.take(3), List("estadisticas", "series", "api"))
        assertEquals(segments.slice(3, 5), List("PD04640PD", "json"))
        assertEquals(segments.last, "esp")
        val from = LocalDate.parse(segments(5))
        val to   = LocalDate.parse(segments(6))
        assertEquals(ChronoUnit.DAYS.between(from, to), 7L)
        assertEquals(request.headers.get(ci"Accept").map(_.head.value), Some("application/json"))
        assert(
          request.headers.get(ci"User-Agent").exists(_.head.value.startsWith("bcrp-fx-alerts"))
        )
    }

  test("una ventana sin dato publicado produce ExchangeRateNotPublished, no un error"):
    scripted(_ => ok(allNotAvailable)) { (client, _) =>
      clientFor(client)
        .latest(series)
        .map(result => assertEquals(result, Left(ExchangeRateNotPublished(series))))
    }

  test("ante 5xx reintenta con espera creciente y termina con éxito si la fuente se recupera"):
    val respond: Int => IO[Response[IO]] =
      case 1 => status(Status.ServiceUnavailable)
      case 2 => status(Status.BadGateway)
      case _ => ok(sampleBody)
    scripted(respond) { (client, calls) =>
      for
        start    <- IO.monotonic
        result   <- clientFor(client).latest(series)
        end      <- IO.monotonic
        requests <- calls.get
      yield
        assertEquals(requests.size, 3)
        assert(result.isRight, result.toString)
        // 500 ms antes del segundo intento y 1 000 ms antes del tercero.
        assertEquals(end - start, 1500.millis)
    }

  test("ante 5xx persistente agota los reintentos configurados y responde ExchangeRateUnavailable"):
    scripted(_ => status(Status.InternalServerError)) { (client, calls) =>
      for
        result   <- clientFor(client).latest(series)
        requests <- calls.get
      yield
        assertEquals(requests.size, config.call.maxRetries + 1)
        assertEquals(result, Left(ExchangeRateUnavailable(series)))
    }

  test("ante 4xx no reintenta"):
    scripted(_ => status(Status.NotFound)) { (client, calls) =>
      for
        start    <- IO.monotonic
        result   <- clientFor(client).latest(series)
        end      <- IO.monotonic
        requests <- calls.get
      yield
        assertEquals(requests.size, 1)
        assertEquals(result, Left(ExchangeRateUnavailable(series)))
        assertEquals(end - start, Duration.Zero)
    }

  test("ante tiempo de espera agotado reintenta y cada intento respeta el presupuesto configurado"):
    scripted(_ => IO.never) { (client, calls) =>
      for
        start    <- IO.monotonic
        result   <- clientFor(client).latest(series)
        end      <- IO.monotonic
        requests <- calls.get
      yield
        assertEquals(requests.size, 3)
        assertEquals(result, Left(ExchangeRateUnavailable(series)))
        // 3 intentos de (1 s + 2 s) más las esperas de 500 ms y 1 000 ms.
        assertEquals(end - start, 3 * config.call.attemptTimeout + 1500.millis)
    }

  test("un tiempo de espera agotado seguido de éxito devuelve el dato"):
    val respond: Int => IO[Response[IO]] =
      case 1 => IO.never
      case _ => ok(sampleBody)
    scripted(respond) { (client, calls) =>
      for
        result   <- clientFor(client).latest(series)
        requests <- calls.get
      yield
        assertEquals(requests.size, 2)
        assert(result.isRight, result.toString)
    }

  test("un 200 con cuerpo HTML (desafío del proxy) es un fallo no transitorio: no reintenta"):
    scripted(_ => ok(challengePage)) { (client, calls) =>
      for
        result   <- clientFor(client).latest(series)
        requests <- calls.get
      yield
        assertEquals(requests.size, 1)
        assertEquals(result, Left(ExchangeRateUnavailable(series)))
    }

  test("un fallo de conexión se trata como transitorio"):
    val respond: Int => IO[Response[IO]] =
      case 1 => IO.raiseError(new java.net.ConnectException("Connection refused"))
      case _ => ok(sampleBody)
    scripted(respond) { (client, calls) =>
      for
        result   <- clientFor(client).latest(series)
        requests <- calls.get
      yield
        assertEquals(requests.size, 2)
        assert(result.isRight, result.toString)
    }

  test("con maxRetries = 0 solo hay un intento"):
    scripted(_ => status(Status.ServiceUnavailable)) { (client, calls) =>
      for
        result <- clientFor(client, config.copy(call = config.call.copy(maxRetries = 0)))
          .latest(series)
        requests <- calls.get
      yield
        assertEquals(requests.size, 1)
        assertEquals(result, Left(ExchangeRateUnavailable(series)))
    }
