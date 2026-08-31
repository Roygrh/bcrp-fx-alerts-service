package pe.quiroz.fxalerts.infrastructure.http.alert

import io.circe.Codec
import pe.quiroz.fxalerts.application.alert.{AlertEvaluation, EvaluatedAlert}
import pe.quiroz.fxalerts.application.rate.Freshness
import pe.quiroz.fxalerts.domain.alert.{
  AlertOutcome,
  AlertStatus,
  CrossingDirection,
  EvaluationBasis
}
import pe.quiroz.fxalerts.infrastructure.http.rate.RateResponse
import sttp.tapir.Schema

import java.time.Instant
import java.util.UUID

import AlertJson.given

/**
 * Una alerta evaluada: su representación pública habitual más el resultado.
 *
 * La alerta se anida completa en lugar de repetir sus campos: el consumidor recibe exactamente la
 * misma forma que en `GET /api/v1/alerts`, y el contrato de la alerta evoluciona en un solo lugar.
 */
final case class EvaluatedAlertResponse(alert: AlertResponse, outcome: AlertOutcome)
    derives Codec.AsObject,
      Schema

object EvaluatedAlertResponse:

  def from(evaluated: EvaluatedAlert): EvaluatedAlertResponse =
    EvaluatedAlertResponse(AlertResponse.from(evaluated.alert), evaluated.outcome)

/**
 * Representación pública de la evaluación de las alertas de un cliente.
 *
 * El tipo de cambio usado se expone con la misma representación que `GET /api/v1/rates/current`
 * (`rate`), de modo que procedencia, oficialidad y frescura se leen igual en ambos recursos.
 * `basis` y `conclusive` resumen esa información en la dimensión que necesita quien decide si
 * actúa: todas las alertas de la respuesta se evaluaron contra el mismo dato, así que la base es
 * única y se expresa una vez, no en cada elemento.
 *
 * @param evaluatedAt
 *   instante en que se aplicó la regla; `rate.ageSeconds` se calcula respecto a él
 * @param rate
 *   tipo de cambio usado, con procedencia (`source`) y frescura (`freshness`)
 * @param basis
 *   calidad del dato como fundamento para actuar
 * @param conclusive
 *   `true` solo si `basis` es `OFFICIAL_CONFIRMED`; un sistema que actúe automáticamente puede
 *   decidir con este único campo y `outcome`
 * @param items
 *   todas las alertas del cliente con su resultado, en el orden del listado; vacío si el cliente no
 *   tiene alertas
 */
final case class AlertEvaluationResponse(
    evaluatedAt: Instant,
    rate: RateResponse,
    basis: EvaluationBasis,
    conclusive: Boolean,
    items: List[EvaluatedAlertResponse]
) derives Codec.AsObject,
      Schema

object AlertEvaluationResponse:

  def from(evaluation: AlertEvaluation): AlertEvaluationResponse =
    AlertEvaluationResponse(
      evaluatedAt = evaluation.evaluatedAt,
      rate = RateResponse.from(evaluation.snapshot, evaluation.evaluatedAt),
      basis = evaluation.basis,
      conclusive = evaluation.basis.conclusive,
      items = evaluation.alerts.map(EvaluatedAlertResponse.from)
    )

  private val evaluatedAt = Instant.parse("2026-08-30T15:30:42Z")

  private val triggeredAbove = AlertResponse.example.copy(threshold = BigDecimal("3.50"))

  private val notTriggeredBelow = AlertResponse.example.copy(
    id = UUID.fromString("2b7e4d10-5c3a-4f8e-9a1b-6d0c3e2f1a44"),
    threshold = BigDecimal("3.40"),
    direction = CrossingDirection.Below
  )

  private val inactive = AlertResponse.example.copy(
    id = UUID.fromString("c9d2e1f0-3a4b-4c5d-8e6f-7a8b9c0d1e2f"),
    threshold = BigDecimal("3.50"),
    status = AlertStatus.Inactive,
    updatedAt = Instant.parse("2026-08-30T16:00:00Z")
  )

  /** Precio oficial confirmado: la única base concluyente. */
  val example: AlertEvaluationResponse = AlertEvaluationResponse(
    evaluatedAt = evaluatedAt,
    rate = RateResponse.example,
    basis = EvaluationBasis.OfficialConfirmed,
    conclusive = true,
    items = List(
      EvaluatedAlertResponse(triggeredAbove, AlertOutcome.Triggered),
      EvaluatedAlertResponse(notTriggeredBelow, AlertOutcome.NotTriggered),
      EvaluatedAlertResponse(inactive, AlertOutcome.Inactive)
    )
  )

  /** Evaluada sobre el respaldo no oficial: disparada, pero sobre una referencia de mercado. */
  val exampleMarketReference: AlertEvaluationResponse = AlertEvaluationResponse(
    evaluatedAt = evaluatedAt,
    rate = RateResponse.exampleFallback,
    basis = EvaluationBasis.MarketReference,
    conclusive = false,
    items = List(
      EvaluatedAlertResponse(notTriggeredBelow, AlertOutcome.Triggered),
      EvaluatedAlertResponse(triggeredAbove, AlertOutcome.NotTriggered)
    )
  )

  /** Ninguna fuente responde: se evalúa sobre el último valor conocido, sin confirmar. */
  val exampleUnconfirmed: AlertEvaluationResponse = example.copy(
    rate = RateResponse.example.copy(ageSeconds = 7_242L, freshness = Freshness.Stale),
    basis = EvaluationBasis.Unconfirmed,
    conclusive = false
  )

  /** Cliente sin alertas: 200 con la evaluación vacía, nunca 404. */
  val exampleEmpty: AlertEvaluationResponse = example.copy(items = Nil)
