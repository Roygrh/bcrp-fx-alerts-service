package pe.quiroz.fxalerts.infrastructure.cache

import cats.effect.IO
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import pe.quiroz.fxalerts.application.rate.{ExchangeRateSource, Freshness, StubExchangeRateSource}
import pe.quiroz.fxalerts.domain.DomainError.{ExchangeRateNotPublished, ExchangeRateUnavailable}
import pe.quiroz.fxalerts.domain.alert.BcrpSeries
import pe.quiroz.fxalerts.infrastructure.config.RateCacheConfig

import java.util.concurrent.TimeoutException
import scala.concurrent.duration.*

/** Caché del tipo de cambio con reloj virtual (`TestControl`): ningún `sleep` espera de verdad. */
class CachedExchangeRateSourceSuite extends CatsEffectSuite:

  private given Logger[IO] = Slf4jLogger.getLogger[IO]

  private val series = BcrpSeries.UsdPenSbsSell

  private val config =
    RateCacheConfig(ttl = 15.minutes, maxStale = 24.hours, failureBackoff = 1.minute)

  private val unavailable = ExchangeRateUnavailable(series)

  private def withCache[A](
      source: ExchangeRateSource[IO],
      config: RateCacheConfig = config
  )(body: CachedExchangeRateSource[IO] => IO[A]): IO[A] =
    TestControl.executeEmbed(CachedExchangeRateSource.resource[IO](source, config).use(body))

  test("acierto: dentro del TTL la fuente se consulta una sola vez y el dato se sirve FRESH"):
    StubExchangeRateSource(StubExchangeRateSource.freshSample).flatMap { source =>
      withCache(source) { cache =>
        for
          first  <- cache.latest(series)
          _      <- IO.sleep(5.minutes)
          second <- cache.latest(series)
          calls  <- source.callCount
        yield
          assertEquals(calls, 1)
          assertEquals(first.map(_.rate), Right(StubExchangeRateSource.sampleRate))
          assertEquals(second.map(_.freshness), Right(Freshness.Fresh))
          assertEquals(second.map(_.retrievedAt), first.map(_.retrievedAt))
      }
    }

  test("vencimiento: pasado el TTL se vuelve a consultar la fuente"):
    StubExchangeRateSource(StubExchangeRateSource.freshSample).flatMap { source =>
      withCache(source) { cache =>
        for
          first  <- cache.latest(series)
          _      <- IO.sleep(config.ttl + 1.second)
          second <- cache.latest(series)
          calls  <- source.callCount
        yield
          assertEquals(calls, 2)
          assert(second.map(_.retrievedAt) != first.map(_.retrievedAt))
          assertEquals(second.map(_.freshness), Right(Freshness.Fresh))
      }
    }

  test("dato obsoleto: si la fuente falla tras el vencimiento se sirve el último valor como STALE"):
    StubExchangeRateSource(StubExchangeRateSource.freshSample).flatMap { source =>
      withCache(source) { cache =>
        for
          first  <- cache.latest(series)
          _      <- IO.sleep(config.ttl + 1.second)
          _      <- source.respondWith(StubExchangeRateSource.failing(unavailable))
          second <- cache.latest(series)
          calls  <- source.callCount
        yield
          assertEquals(calls, 2)
          assertEquals(second.map(_.rate), Right(StubExchangeRateSource.sampleRate))
          assertEquals(second.map(_.freshness), Right(Freshness.Stale))
          assertEquals(second.map(_.retrievedAt), first.map(_.retrievedAt))
      }
    }

  test("tras un fallo, el dato obsoleto se sirve sin reconsultar durante failureBackoff"):
    StubExchangeRateSource(StubExchangeRateSource.freshSample).flatMap { source =>
      withCache(source) { cache =>
        for
          _          <- cache.latest(series)
          _          <- IO.sleep(config.ttl + 1.second)
          _          <- source.respondWith(StubExchangeRateSource.failing(unavailable))
          _          <- cache.latest(series)
          duringHold <- cache.latest(series)
          callsHold  <- source.callCount
          _          <- IO.sleep(config.failureBackoff + 1.second)
          afterHold  <- cache.latest(series)
          callsAfter <- source.callCount
        yield
          assertEquals(callsHold, 2)
          assertEquals(duringHold.map(_.freshness), Right(Freshness.Stale))
          assertEquals(callsAfter, 3)
          assertEquals(afterHold.map(_.freshness), Right(Freshness.Stale))
      }
    }

  test("la fuente recuperada tras servir dato obsoleto vuelve a entregar FRESH"):
    StubExchangeRateSource(StubExchangeRateSource.freshSample).flatMap { source =>
      withCache(source) { cache =>
        for
          _         <- cache.latest(series)
          _         <- IO.sleep(config.ttl + 1.second)
          _         <- source.respondWith(StubExchangeRateSource.failing(unavailable))
          stale     <- cache.latest(series)
          _         <- IO.sleep(config.failureBackoff + 1.second)
          _         <- source.respondWith(StubExchangeRateSource.freshSample)
          recovered <- cache.latest(series)
        yield
          assertEquals(stale.map(_.freshness), Right(Freshness.Stale))
          assertEquals(recovered.map(_.freshness), Right(Freshness.Fresh))
      }
    }

  test(
    "más allá de maxStale el dato obsoleto ya no se sirve: la fuente caída es ExchangeRateUnavailable"
  ):
    StubExchangeRateSource(StubExchangeRateSource.freshSample).flatMap { source =>
      withCache(source) { cache =>
        for
          _      <- cache.latest(series)
          _      <- IO.sleep(config.maxStale + 1.minute)
          _      <- source.respondWith(StubExchangeRateSource.failing(unavailable))
          result <- cache.latest(series)
        yield assertEquals(result, Left(unavailable))
      }
    }

  test("con la caché vacía y la fuente caída el resultado es ExchangeRateUnavailable"):
    StubExchangeRateSource(StubExchangeRateSource.failing(unavailable)).flatMap { source =>
      withCache(source) { cache =>
        for
          first  <- cache.latest(series)
          _      <- IO.sleep(config.failureBackoff + 1.second)
          _      <- source.respondWith(StubExchangeRateSource.freshSample)
          second <- cache.latest(series)
          calls  <- source.callCount
        yield
          assertEquals(first, Left(unavailable))
          assertEquals(second.map(_.freshness), Right(Freshness.Fresh))
          assertEquals(calls, 2)
      }
    }

  test("con la caché vacía, un fallo se retiene durante failureBackoff sin reconsultar la fuente"):
    StubExchangeRateSource(StubExchangeRateSource.failing(unavailable)).flatMap { source =>
      withCache(source) { cache =>
        for
          first      <- cache.latest(series)
          second     <- cache.latest(series)
          callsHold  <- source.callCount
          _          <- IO.sleep(config.failureBackoff + 1.second)
          third      <- cache.latest(series)
          callsAfter <- source.callCount
        yield
          assertEquals(first, Left(unavailable))
          assertEquals(second, Left(unavailable))
          assertEquals(callsHold, 1)
          assertEquals(third, Left(unavailable))
          assertEquals(callsAfter, 2)
      }
    }

  test("una excepción de la fuente se trata como fuente no disponible"):
    StubExchangeRateSource(IO.raiseError(new RuntimeException("boom"))).flatMap { source =>
      withCache(source) { cache =>
        cache.latest(series).map(result => assertEquals(result, Left(unavailable)))
      }
    }

  test("sin dato publicado se respeta la respuesta de la fuente aunque exista un valor anterior"):
    StubExchangeRateSource(StubExchangeRateSource.freshSample).flatMap { source =>
      withCache(source) { cache =>
        for
          _ <- cache.latest(series)
          _ <- IO.sleep(config.ttl + 1.second)
          _ <- source.respondWith(StubExchangeRateSource.failing(ExchangeRateNotPublished(series)))
          result <- cache.latest(series)
        yield assertEquals(result, Left(ExchangeRateNotPublished(series)))
      }
    }

  test(
    "concurrencia: peticiones simultáneas con la caché vacía provocan una sola llamada a la fuente"
  ):
    val slowSource = IO.sleep(200.millis) *> StubExchangeRateSource.freshSample
    StubExchangeRateSource(slowSource).flatMap { source =>
      withCache(source) { cache =>
        for
          results <- (1 to 25).toList.parTraverse(_ => cache.latest(series))
          calls   <- source.callCount
        yield
          assertEquals(calls, 1)
          assert(results.forall(_.isRight))
          assertEquals(results.map(_.map(_.retrievedAt)).distinct.size, 1)
      }
    }

  test("si quien dispara la consulta se cancela, la consulta continúa y alimenta la caché"):
    val slowSource = IO.sleep(2.seconds) *> StubExchangeRateSource.freshSample
    StubExchangeRateSource(slowSource).flatMap { source =>
      withCache(source) { cache =>
        for
          interrupted <- cache.latest(series).timeout(500.millis).attempt
          _           <- IO.sleep(2.seconds)
          result      <- cache.latest(series)
          calls       <- source.callCount
        yield
          assert(interrupted.left.exists(_.isInstanceOf[TimeoutException]))
          assertEquals(calls, 1)
          assertEquals(result.map(_.freshness), Right(Freshness.Fresh))
      }
    }
