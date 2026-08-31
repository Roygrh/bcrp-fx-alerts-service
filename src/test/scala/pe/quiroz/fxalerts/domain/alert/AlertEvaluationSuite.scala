package pe.quiroz.fxalerts.domain.alert

import munit.FunSuite
import pe.quiroz.fxalerts.domain.rate.{ExchangeRate, RateProvider}

import java.time.{Instant, LocalDate}
import java.util.UUID

/**
 * Regla de cruce y calidad del dato, sin efectos: cada combinación de sentido, estado y relación
 * con el umbral, incluidos la igualdad exacta y la serie distinta.
 */
class AlertEvaluationSuite extends FunSuite:

  private val threshold = BigDecimal("3.80")
  private val createdAt = Instant.parse("2026-08-28T15:00:00Z")
  private val clientId  = ClientId.from("cliente-001").toOption.get

  private def alert(
      direction: CrossingDirection,
      status: AlertStatus = AlertStatus.Active,
      series: BcrpSeries = BcrpSeries.UsdPenSbsSell,
      threshold: BigDecimal = threshold
  ): Alert =
    val created = Alert
      .create(AlertId(UUID.randomUUID()), clientId, series, threshold, direction, createdAt)
      .fold(error => fail(s"Alerta de prueba inválida: ${error.message}"), identity)
    if status == AlertStatus.Active then created
    else
      created
        .update(series, threshold, direction, status, createdAt)
        .fold(error => fail(s"Alerta de prueba inválida: ${error.message}"), identity)

  private def rate(
      value: BigDecimal,
      provider: RateProvider = RateProvider.Bcrp,
      series: BcrpSeries = BcrpSeries.UsdPenSbsSell
  ): ExchangeRate =
    ExchangeRate(series, LocalDate.of(2026, 8, 28), value, provider)

  // --- Tabla completa: sentido × estado × relación del valor con el umbral ----------------------

  private val relations: List[(String, BigDecimal)] = List(
    "por debajo" -> BigDecimal("3.799"),
    "igual"      -> BigDecimal("3.80"),
    "por encima" -> BigDecimal("3.801")
  )

  private def expected(
      direction: CrossingDirection,
      status: AlertStatus,
      value: BigDecimal
  ): AlertOutcome =
    (status, direction) match
      case (AlertStatus.Inactive, _) => AlertOutcome.Inactive
      case (AlertStatus.Active, CrossingDirection.Above) if value > threshold =>
        AlertOutcome.Triggered
      case (AlertStatus.Active, CrossingDirection.Below) if value < threshold =>
        AlertOutcome.Triggered
      case (AlertStatus.Active, _) => AlertOutcome.NotTriggered

  for
    direction         <- CrossingDirection.values.toList
    status            <- AlertStatus.values.toList
    (relation, value) <- relations
  do
    val outcome = expected(direction, status, value)
    test(s"$direction $status con valor $relation del umbral ($value vs 3.80) -> $outcome"):
      assertEquals(alert(direction, status).evaluate(rate(value)), outcome)

  // --- Igualdad exacta ---------------------------------------------------------------------------

  test("un valor exactamente igual al umbral no dispara en ningún sentido"):
    assertEquals(
      alert(CrossingDirection.Above).evaluate(rate(threshold)),
      AlertOutcome.NotTriggered
    )
    assertEquals(
      alert(CrossingDirection.Below).evaluate(rate(threshold)),
      AlertOutcome.NotTriggered
    )

  test("la igualdad es numérica, no textual: 3.800 y 3.8 son el mismo valor que 3.80"):
    List(BigDecimal("3.8"), BigDecimal("3.800"), BigDecimal("3.8000")).foreach { value =>
      assertEquals(alert(CrossingDirection.Above).evaluate(rate(value)), AlertOutcome.NotTriggered)
      assertEquals(alert(CrossingDirection.Below).evaluate(rate(value)), AlertOutcome.NotTriggered)
    }

  test("la semántica inclusiva se expresa con el umbral: ABOVE 3.7999 dispara con 3.800"):
    val inclusive = alert(CrossingDirection.Above, threshold = BigDecimal("3.7999"))
    assertEquals(inclusive.evaluate(rate(BigDecimal("3.800"))), AlertOutcome.Triggered)
    assertEquals(inclusive.evaluate(rate(BigDecimal("3.799"))), AlertOutcome.NotTriggered)

  test("dos alertas opuestas sobre el mismo umbral nunca se disparan a la vez"):
    val above = alert(CrossingDirection.Above)
    val below = alert(CrossingDirection.Below)
    List("3.0", "3.799", "3.7999", "3.80", "3.8001", "3.801", "4.5").map(BigDecimal(_)).foreach {
      value =>
        val outcomes = List(above.evaluate(rate(value)), below.evaluate(rate(value)))
        assert(outcomes.count(_.triggered) <= 1, s"valor $value: $outcomes")
    }

  // --- Serie distinta ----------------------------------------------------------------------------

  test("una alerta se evalúa solo contra su propia serie; otra serie es SeriesMismatch"):
    // Recorre todos los pares del catálogo: con una sola serie solo existe el par coincidente, y
    // la rama de serie distinta se ejercita en cuanto se incorpore una segunda serie.
    for
      alertSeries <- BcrpSeries.values.toList
      rateSeries  <- BcrpSeries.values.toList
    do
      val outcome = alert(CrossingDirection.Above, series = alertSeries)
        .evaluate(rate(BigDecimal("3.90"), series = rateSeries))
      if alertSeries == rateSeries then assertEquals(outcome, AlertOutcome.Triggered)
      else assertEquals(outcome, AlertOutcome.SeriesMismatch)

  test("una alerta inactiva es Inactive sea cual sea la serie del dato"):
    for rateSeries <- BcrpSeries.values.toList do
      assertEquals(
        alert(CrossingDirection.Above, AlertStatus.Inactive)
          .evaluate(rate(BigDecimal("3.90"), series = rateSeries)),
        AlertOutcome.Inactive
      )

  // --- Procedencia -------------------------------------------------------------------------------

  test("la procedencia del dato no altera el cruce: el mismo valor produce el mismo resultado"):
    RateProvider.values.foreach { provider =>
      assertEquals(
        alert(CrossingDirection.Above).evaluate(rate(BigDecimal("3.81"), provider)),
        AlertOutcome.Triggered
      )
      assertEquals(
        alert(CrossingDirection.Above).evaluate(rate(BigDecimal("3.79"), provider)),
        AlertOutcome.NotTriggered
      )
    }

  // --- Resultado ---------------------------------------------------------------------------------

  test("solo Triggered es triggered; solo Triggered y NotTriggered son evaluated"):
    assertEquals(AlertOutcome.values.filter(_.triggered).toList, List(AlertOutcome.Triggered))
    assertEquals(
      AlertOutcome.values.filter(_.evaluated).toList,
      List(AlertOutcome.Triggered, AlertOutcome.NotTriggered)
    )

  // --- Base de la evaluación ---------------------------------------------------------------------

  test("EvaluationBasis: oficial y confirmado es la única base concluyente"):
    val official = rate(threshold, RateProvider.Bcrp)
    val market   = rate(threshold, RateProvider.ExchangeRateApi)
    assertEquals(EvaluationBasis.of(official, confirmed = true), EvaluationBasis.OfficialConfirmed)
    assertEquals(EvaluationBasis.of(market, confirmed = true), EvaluationBasis.MarketReference)
    assertEquals(EvaluationBasis.of(official, confirmed = false), EvaluationBasis.Unconfirmed)
    assertEquals(EvaluationBasis.of(market, confirmed = false), EvaluationBasis.Unconfirmed)
    assertEquals(
      EvaluationBasis.values.filter(_.conclusive).toList,
      List(EvaluationBasis.OfficialConfirmed)
    )
