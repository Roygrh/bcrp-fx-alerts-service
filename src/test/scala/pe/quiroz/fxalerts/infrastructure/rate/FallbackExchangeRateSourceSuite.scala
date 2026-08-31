package pe.quiroz.fxalerts.infrastructure.rate

import cats.data.NonEmptyList
import cats.effect.IO
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import pe.quiroz.fxalerts.application.rate.StubExchangeRateSource
import pe.quiroz.fxalerts.domain.DomainError.{ExchangeRateNotPublished, ExchangeRateUnavailable}
import pe.quiroz.fxalerts.domain.alert.BcrpSeries
import pe.quiroz.fxalerts.domain.rate.RateProvider

/** Compositor de fuentes: reglas de salto al respaldo, sin red. */
class FallbackExchangeRateSourceSuite extends CatsEffectSuite:

  private given Logger[IO] = Slf4jLogger.getLogger[IO]

  private val series       = BcrpSeries.UsdPenSbsSell
  private val unavailable  = ExchangeRateUnavailable(series)
  private val notPublished = ExchangeRateNotPublished(series)

  private def chain(entries: (RateProvider, StubExchangeRateSource)*) =
    FallbackExchangeRateSource[IO](
      NonEmptyList.fromListUnsafe(entries.toList.map((p, s) => ProviderSource[IO](p, s)))
    )

  test("sirve desde la primera fuente cuando responde, sin consultar el respaldo"):
    for
      bcrp  <- StubExchangeRateSource(StubExchangeRateSource.freshSampleFrom(RateProvider.Bcrp))
      erapi <- StubExchangeRateSource(
        StubExchangeRateSource.freshSampleFrom(RateProvider.ExchangeRateApi)
      )
      result <- chain(RateProvider.Bcrp -> bcrp, RateProvider.ExchangeRateApi -> erapi)
        .latest(series)
      bcrpCalls  <- bcrp.callCount
      erapiCalls <- erapi.callCount
    yield
      assertEquals(result.map(_.rate.provider), Right(RateProvider.Bcrp))
      assertEquals(result.map(_.rate.official), Right(true))
      assertEquals((bcrpCalls, erapiCalls), (1, 0))

  test("cae al respaldo cuando la primera fuente no pudo responder"):
    for
      bcrp  <- StubExchangeRateSource(StubExchangeRateSource.failing(unavailable))
      erapi <- StubExchangeRateSource(
        StubExchangeRateSource.freshSampleFrom(RateProvider.ExchangeRateApi)
      )
      result <- chain(RateProvider.Bcrp -> bcrp, RateProvider.ExchangeRateApi -> erapi)
        .latest(series)
      bcrpCalls  <- bcrp.callCount
      erapiCalls <- erapi.callCount
    yield
      assertEquals(result.map(_.rate.provider), Right(RateProvider.ExchangeRateApi))
      assertEquals(result.map(_.rate.official), Right(false))
      assertEquals((bcrpCalls, erapiCalls), (1, 1))

  test("NO cae al respaldo cuando la primera fuente responde que no hay dato publicado"):
    for
      bcrp  <- StubExchangeRateSource(StubExchangeRateSource.failing(notPublished))
      erapi <- StubExchangeRateSource(
        StubExchangeRateSource.freshSampleFrom(RateProvider.ExchangeRateApi)
      )
      result <- chain(RateProvider.Bcrp -> bcrp, RateProvider.ExchangeRateApi -> erapi)
        .latest(series)
      erapiCalls <- erapi.callCount
    yield
      assertEquals(result, Left(notPublished))
      assertEquals(erapiCalls, 0)

  test("falla con ExchangeRateUnavailable cuando todas las fuentes fallan"):
    for
      bcrp   <- StubExchangeRateSource(StubExchangeRateSource.failing(unavailable))
      erapi  <- StubExchangeRateSource(StubExchangeRateSource.failing(unavailable))
      result <- chain(RateProvider.Bcrp -> bcrp, RateProvider.ExchangeRateApi -> erapi)
        .latest(series)
      bcrpCalls  <- bcrp.callCount
      erapiCalls <- erapi.callCount
    yield
      assertEquals(result, Left(unavailable))
      assertEquals((bcrpCalls, erapiCalls), (1, 1))

  test("respeta el orden configurado: con el respaldo primero, el BCRP no se consulta"):
    for
      bcrp  <- StubExchangeRateSource(StubExchangeRateSource.freshSampleFrom(RateProvider.Bcrp))
      erapi <- StubExchangeRateSource(
        StubExchangeRateSource.freshSampleFrom(RateProvider.ExchangeRateApi)
      )
      result <- chain(RateProvider.ExchangeRateApi -> erapi, RateProvider.Bcrp -> bcrp)
        .latest(series)
      bcrpCalls <- bcrp.callCount
    yield
      assertEquals(result.map(_.rate.provider), Right(RateProvider.ExchangeRateApi))
      assertEquals(bcrpCalls, 0)

  test("con una sola fuente configurada no hay respaldo: su indisponibilidad es el resultado"):
    for
      bcrp   <- StubExchangeRateSource(StubExchangeRateSource.failing(unavailable))
      result <- chain(RateProvider.Bcrp -> bcrp).latest(series)
    yield assertEquals(result, Left(unavailable))
