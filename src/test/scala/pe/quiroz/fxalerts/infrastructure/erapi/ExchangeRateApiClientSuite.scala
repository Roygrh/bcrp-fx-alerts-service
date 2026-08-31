package pe.quiroz.fxalerts.infrastructure.erapi

import cats.effect.testkit.TestControl
import cats.effect.{IO, Ref}
import munit.CatsEffectSuite
import org.http4s.client.Client
import org.http4s.implicits.*
import org.http4s.{HttpApp, Request, Response, Status}
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import pe.quiroz.fxalerts.application.rate.Freshness
import pe.quiroz.fxalerts.domain.DomainError.ExchangeRateUnavailable
import pe.quiroz.fxalerts.domain.alert.BcrpSeries
import pe.quiroz.fxalerts.domain.rate.RateProvider
import pe.quiroz.fxalerts.infrastructure.config.{ExchangeRateApiConfig, RemoteCallConfig}

import java.time.LocalDate
import scala.concurrent.duration.*
import scala.io.Source

/**
 * Comportamiento del adaptador de respaldo sin red: `Client` sobre una `HttpApp` guionizada y
 * tiempo virtual con `TestControl`. Comparte la política de [[RemoteCall]] con el cliente BCRP, así
 * que aquí se comprueba que la hereda (5xx/timeout reintentan, 4xx y errores del proveedor no).
 */
class ExchangeRateApiClientSuite extends CatsEffectSuite:

  private given Logger[IO] = Slf4jLogger.getLogger[IO]

  private val series = BcrpSeries.UsdPenSbsSell

  private val config = ExchangeRateApiConfig(
    baseUri = uri"http://erapi.test/v6",
    call = RemoteCallConfig(
      connectTimeout = 1.second,
      readTimeout = 2.seconds,
      maxRetries = 2,
      retryBackoff = 500.millis
    )
  )

  private def fixture(name: String): String =
    val stream = getClass.getResourceAsStream(s"/erapi/$name")
    try Source.fromInputStream(stream, "UTF-8").mkString
    finally stream.close()

  private val successBody = fixture("latest-USD.json")
  private val errorBody   = fixture("latest-XXX-error.json")

  private def ok(body: String): IO[Response[IO]] = IO.pure(Response[IO](Status.Ok).withEntity(body))

  private def status(status: Status): IO[Response[IO]] = IO.pure(Response[IO](status))

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

  private def latest(client: Client[IO]) =
    ExchangeRateApiClient[IO](client, config).flatMap(_.latest(series))

  test("camino feliz: tasa USD/PEN marcada como no oficial, con la fecha del dato"):
    scripted(_ => ok(successBody)) { (client, calls) =>
      for
        result   <- latest(client)
        requests <- calls.get
      yield
        assertEquals(requests.size, 1)
        val snapshot = result.fold(e => fail(s"Se esperaba un dato: $e"), identity)
        assertEquals(snapshot.rate.value, BigDecimal("3.350827"))
        assertEquals(snapshot.rate.date, LocalDate.of(2026, 8, 30))
        assertEquals(snapshot.rate.provider, RateProvider.ExchangeRateApi)
        assert(!snapshot.rate.official)
        assertEquals(snapshot.freshness, Freshness.Fresh)
    }

  test("la petición apunta a /latest/USD con cabeceras explícitas"):
    scripted(_ => ok(successBody)) { (client, calls) =>
      for
        _        <- latest(client)
        requests <- calls.get
      yield
        val request = requests.head
        assertEquals(request.uri.path.renderString, "/v6/latest/USD")
        assert(request.headers.headers.exists(_.name.toString.equalsIgnoreCase("User-Agent")))
        assert(request.headers.headers.exists(_.name.toString.equalsIgnoreCase("Accept")))
    }

  test("\"result\": \"error\" con HTTP 200 es fuente no disponible y no se reintenta"):
    scripted(_ => ok(errorBody)) { (client, calls) =>
      for
        result   <- latest(client)
        requests <- calls.get
      yield
        assertEquals(requests.size, 1)
        assertEquals(result, Left(ExchangeRateUnavailable(series)))
    }

  test("una respuesta sin PEN es fuente no disponible y no se reintenta"):
    scripted(_ => ok(successBody.replace("\"PEN\":3.350827,", ""))) { (client, calls) =>
      for
        result   <- latest(client)
        requests <- calls.get
      yield
        assertEquals(requests.size, 1)
        assertEquals(result, Left(ExchangeRateUnavailable(series)))
    }

  test("ante 5xx reintenta con espera creciente y se recupera"):
    val respond: Int => IO[Response[IO]] =
      case 1 => status(Status.BadGateway)
      case 2 => status(Status.ServiceUnavailable)
      case _ => ok(successBody)
    scripted(respond) { (client, calls) =>
      for
        start    <- IO.monotonic
        result   <- latest(client)
        end      <- IO.monotonic
        requests <- calls.get
      yield
        assertEquals(requests.size, 3)
        assert(result.isRight, result.toString)
        assertEquals(end - start, 1500.millis)
    }

  test("ante 5xx persistente agota los reintentos"):
    scripted(_ => status(Status.InternalServerError)) { (client, calls) =>
      for
        result   <- latest(client)
        requests <- calls.get
      yield
        assertEquals(requests.size, config.call.maxRetries + 1)
        assertEquals(result, Left(ExchangeRateUnavailable(series)))
    }

  test("ante 4xx no reintenta"):
    scripted(_ => status(Status.TooManyRequests)) { (client, calls) =>
      for
        result   <- latest(client)
        requests <- calls.get
      yield
        assertEquals(requests.size, 1)
        assertEquals(result, Left(ExchangeRateUnavailable(series)))
    }

  test("ante tiempo de espera agotado reintenta respetando el presupuesto por intento"):
    scripted(_ => IO.never) { (client, calls) =>
      for
        start    <- IO.monotonic
        result   <- latest(client)
        end      <- IO.monotonic
        requests <- calls.get
      yield
        assertEquals(requests.size, 3)
        assertEquals(result, Left(ExchangeRateUnavailable(series)))
        assertEquals(end - start, 3 * config.call.attemptTimeout + 1500.millis)
    }

  test("un fin de vida anunciado no impide servir el dato (solo se registra un aviso)"):
    val body = successBody.replace("\"time_eol_unix\":0", "\"time_eol_unix\":1800000000")
    scripted(_ => ok(body)) { (client, _) =>
      for
        source <- ExchangeRateApiClient[IO](client, config)
        first  <- source.latest(series)
        second <- source.latest(series)
      yield
        assert(first.isRight, first.toString)
        assert(second.isRight, second.toString)
    }
