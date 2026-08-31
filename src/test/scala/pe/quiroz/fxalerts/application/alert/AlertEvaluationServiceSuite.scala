package pe.quiroz.fxalerts.application.alert

import cats.effect.IO
import cats.syntax.all.*
import munit.CatsEffectSuite
import pe.quiroz.fxalerts.application.rate.{
  ExchangeRateService,
  Freshness,
  RateSnapshot,
  StubExchangeRateSource
}
import pe.quiroz.fxalerts.domain.DomainError.{
  ExchangeRateError,
  ExchangeRateNotPublished,
  ExchangeRateUnavailable
}
import pe.quiroz.fxalerts.domain.alert.*
import pe.quiroz.fxalerts.domain.rate.RateProvider

import java.time.Instant
import java.util.UUID

/**
 * Evaluación sin red ni base de datos: repositorio en memoria y fuente de tipo de cambio
 * guionizada. El dato oficial de muestra vale 3.523 y el del respaldo 3.350827.
 */
class AlertEvaluationServiceSuite extends CatsEffectSuite:

  private val owner = ClientId.from("cliente-001").toOption.get
  private val other = ClientId.from("cliente-002").toOption.get

  private val createdAt = Instant.parse("2026-08-28T15:00:00Z")

  private def alert(
      client: ClientId,
      threshold: String,
      direction: CrossingDirection,
      status: AlertStatus = AlertStatus.Active,
      createdAt: Instant = createdAt
  ): Alert =
    val created = Alert
      .create(
        AlertId(UUID.randomUUID()),
        client,
        BcrpSeries.UsdPenSbsSell,
        BigDecimal(threshold),
        direction,
        createdAt
      )
      .fold(error => throw new AssertionError(error.message), identity)
    if status == AlertStatus.Active then created
    else
      created
        .update(created.series, created.threshold.value, direction, status, createdAt)
        .fold(error => throw new AssertionError(error.message), identity)

  private def withService[A](
      behaviour: IO[Either[ExchangeRateError, RateSnapshot]]
  )(body: (AlertEvaluationService[IO], InMemoryAlertRepository) => IO[A]): IO[A] =
    for
      repository <- InMemoryAlertRepository.empty
      source     <- StubExchangeRateSource(behaviour)
      service = AlertEvaluationService[IO](repository, ExchangeRateService[IO](source))
      result <- body(service, repository)
    yield result

  private def rightOrFail[A](result: Either[ExchangeRateError, A]): IO[A] =
    IO.fromEither(result.left.map(error => new AssertionError(error.message)))

  /** Alertas del propietario en orden de creación, con el resultado esperado frente a 3.523. */
  private def ownerAlerts: List[(Alert, AlertOutcome)] = List(
    alert(owner, "3.50", CrossingDirection.Above) -> AlertOutcome.Triggered,
    alert(owner, "3.60", CrossingDirection.Below, createdAt = createdAt.plusSeconds(1)) ->
      AlertOutcome.Triggered,
    alert(owner, "3.60", CrossingDirection.Above, createdAt = createdAt.plusSeconds(2)) ->
      AlertOutcome.NotTriggered,
    alert(
      owner,
      "3.00",
      CrossingDirection.Above,
      AlertStatus.Inactive,
      createdAt.plusSeconds(3)
    ) -> AlertOutcome.Inactive
  )

  // --- Aislamiento por cliente -----------------------------------------------------------------

  test("evalúa todas las alertas del propietario, en orden, y ninguna de otros clientes"):
    withService(StubExchangeRateSource.freshSample) { (service, repository) =>
      val mine    = ownerAlerts
      val foreign = alert(other, "3.00", CrossingDirection.Above) // se dispararía si se evaluara
      for
        _          <- mine.traverse_((a, _) => repository.create(a))
        _          <- repository.create(foreign)
        evaluation <- service.evaluate(owner).flatMap(rightOrFail)
        theirs     <- service.evaluate(other).flatMap(rightOrFail)
        nobody <- service.evaluate(ClientId.from("cliente-999").toOption.get).flatMap(rightOrFail)
      yield
        assertEquals(
          evaluation.alerts.map(e => (e.alert.id, e.outcome)),
          mine.map((a, outcome) => (a.id, outcome))
        )
        assert(evaluation.alerts.forall(_.alert.clientId == owner))
        assertEquals(evaluation.triggered.map(_.alert.id), mine.take(2).map(_._1.id))
        assertEquals(
          theirs.alerts.map(e => (e.alert.id, e.outcome)),
          List(foreign.id -> AlertOutcome.Triggered)
        )
        assertEquals(nobody.alerts, Nil)
    }

  test("un cliente sin alertas obtiene una evaluación vacía con el dato usado"):
    withService(StubExchangeRateSource.freshSample) { (service, _) =>
      service.evaluate(owner).flatMap(rightOrFail).map { evaluation =>
        assertEquals(evaluation.alerts, Nil)
        assertEquals(evaluation.snapshot.rate, StubExchangeRateSource.sampleRate)
        assertEquals(evaluation.basis, EvaluationBasis.OfficialConfirmed)
      }
    }

  // --- Calidad del dato ------------------------------------------------------------------------

  test("dato oficial FRESH: base OfficialConfirmed, concluyente, con el snapshot íntegro"):
    val retrievedAt = Instant.parse("2026-08-30T15:30:00Z")
    val snapshot    = StubExchangeRateSource.snapshotAt(retrievedAt)
    withService(StubExchangeRateSource.fixed(snapshot)) { (service, repository) =>
      for
        _          <- repository.create(alert(owner, "3.50", CrossingDirection.Above))
        before     <- IO.realTimeInstant
        evaluation <- service.evaluate(owner).flatMap(rightOrFail)
        after      <- IO.realTimeInstant
      yield
        assertEquals(evaluation.snapshot, snapshot)
        assertEquals(evaluation.basis, EvaluationBasis.OfficialConfirmed)
        assert(evaluation.basis.conclusive)
        assertEquals(evaluation.alerts.map(_.outcome), List(AlertOutcome.Triggered))
        assert(!evaluation.evaluatedAt.isBefore(before) && !evaluation.evaluatedAt.isAfter(after))
    }

  test(
    "dato del respaldo no oficial FRESH: base MarketReference, no concluyente, cruce sobre 3.350827"
  ):
    withService(StubExchangeRateSource.freshSampleFrom(RateProvider.ExchangeRateApi)) {
      (service, repository) =>
        for
          _ <- repository.create(alert(owner, "3.50", CrossingDirection.Above))
          _ <- repository.create(
            alert(owner, "3.60", CrossingDirection.Below, createdAt = createdAt.plusSeconds(1))
          )
          evaluation <- service.evaluate(owner).flatMap(rightOrFail)
        yield
          assertEquals(evaluation.snapshot.rate.provider, RateProvider.ExchangeRateApi)
          assertEquals(evaluation.basis, EvaluationBasis.MarketReference)
          assert(!evaluation.basis.conclusive)
          assertEquals(
            evaluation.alerts.map(_.outcome),
            List(AlertOutcome.NotTriggered, AlertOutcome.Triggered)
          )
    }

  test("dato STALE: base Unconfirmed sea cual sea la fuente, y el cruce se calcula igualmente"):
    RateProvider.values.toList.traverse_ { provider =>
      val snapshot = StubExchangeRateSource.snapshotAt(
        Instant.parse("2026-08-30T15:30:00Z"),
        Freshness.Stale,
        provider
      )
      withService(StubExchangeRateSource.fixed(snapshot)) { (service, repository) =>
        for
          _          <- repository.create(alert(owner, "3.00", CrossingDirection.Above))
          evaluation <- service.evaluate(owner).flatMap(rightOrFail)
        yield
          assertEquals(evaluation.basis, EvaluationBasis.Unconfirmed, provider.toString)
          assert(!evaluation.basis.conclusive)
          assertEquals(evaluation.snapshot.freshness, Freshness.Stale)
          assertEquals(evaluation.alerts.map(_.outcome), List(AlertOutcome.Triggered))
      }
    }

  // --- Sin tipo de cambio ----------------------------------------------------------------------

  test("sin ninguna fuente disponible devuelve ExchangeRateUnavailable aunque haya alertas"):
    val error = ExchangeRateUnavailable(BcrpSeries.UsdPenSbsSell)
    withService(StubExchangeRateSource.failing(error)) { (service, repository) =>
      for
        _      <- repository.create(alert(owner, "3.00", CrossingDirection.Above))
        result <- service.evaluate(owner)
      yield assertEquals(result, Left(error))
    }

  test("sin dato publicado devuelve ExchangeRateNotPublished"):
    val error = ExchangeRateNotPublished(BcrpSeries.UsdPenSbsSell)
    withService(StubExchangeRateSource.failing(error)) { (service, _) =>
      service.evaluate(owner).map(result => assertEquals(result, Left(error)))
    }
