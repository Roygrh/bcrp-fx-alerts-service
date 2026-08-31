package pe.quiroz.fxalerts.application.rate

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import pe.quiroz.fxalerts.domain.DomainError.ExchangeRateError
import pe.quiroz.fxalerts.domain.alert.BcrpSeries
import pe.quiroz.fxalerts.domain.rate.{ExchangeRate, RateProvider}

import java.time.{Instant, LocalDate}

/**
 * Doble de [[ExchangeRateSource]] para pruebas: responde con el efecto guionizado en `behaviour`
 * (que puede cambiarse sobre la marcha) y cuenta las llamadas recibidas.
 */
final class StubExchangeRateSource(
    behaviour: Ref[IO, IO[Either[ExchangeRateError, RateSnapshot]]],
    calls: Ref[IO, Int]
) extends ExchangeRateSource[IO]:

  def latest(series: BcrpSeries): IO[Either[ExchangeRateError, RateSnapshot]] =
    calls.update(_ + 1) *> behaviour.get.flatten

  def callCount: IO[Int] = calls.get

  def respondWith(next: IO[Either[ExchangeRateError, RateSnapshot]]): IO[Unit] =
    behaviour.set(next)

object StubExchangeRateSource:

  /** Dato oficial de muestra (BCRP). */
  val sampleRate: ExchangeRate = sampleRateFrom(RateProvider.Bcrp)

  def sampleRateFrom(provider: RateProvider): ExchangeRate =
    provider match
      case RateProvider.Bcrp =>
        ExchangeRate(
          BcrpSeries.UsdPenSbsSell,
          LocalDate.of(2026, 8, 28),
          BigDecimal("3.523"),
          provider
        )
      case RateProvider.ExchangeRateApi =>
        ExchangeRate(
          BcrpSeries.UsdPenSbsSell,
          LocalDate.of(2026, 8, 30),
          BigDecimal("3.350827"),
          provider
        )

  /**
   * Instantánea `Fresh` con `retrievedAt` igual al reloj del efecto (compatible con `TestControl`).
   */
  val freshSample: IO[Either[ExchangeRateError, RateSnapshot]] = freshSampleFrom(RateProvider.Bcrp)

  def freshSampleFrom(provider: RateProvider): IO[Either[ExchangeRateError, RateSnapshot]] =
    IO.realTimeInstant.map(now =>
      Right(RateSnapshot(sampleRateFrom(provider), now, Freshness.Fresh))
    )

  def fixed(snapshot: RateSnapshot): IO[Either[ExchangeRateError, RateSnapshot]] =
    IO.pure(Right(snapshot))

  def failing(error: ExchangeRateError): IO[Either[ExchangeRateError, RateSnapshot]] =
    IO.pure(Left(error))

  def apply(initial: IO[Either[ExchangeRateError, RateSnapshot]]): IO[StubExchangeRateSource] =
    (Ref.of[IO, IO[Either[ExchangeRateError, RateSnapshot]]](initial), Ref.of[IO, Int](0))
      .mapN(new StubExchangeRateSource(_, _))

  def snapshotAt(
      retrievedAt: Instant,
      freshness: Freshness = Freshness.Fresh,
      provider: RateProvider = RateProvider.Bcrp
  ): RateSnapshot =
    RateSnapshot(sampleRateFrom(provider), retrievedAt, freshness)
