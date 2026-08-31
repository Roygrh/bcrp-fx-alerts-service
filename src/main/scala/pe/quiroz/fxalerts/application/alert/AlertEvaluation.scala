package pe.quiroz.fxalerts.application.alert

import pe.quiroz.fxalerts.application.rate.{Freshness, RateSnapshot}
import pe.quiroz.fxalerts.domain.alert.{Alert, AlertOutcome, EvaluationBasis}

import java.time.Instant

/** Una alerta junto con el resultado de evaluarla. */
final case class EvaluatedAlert(alert: Alert, outcome: AlertOutcome)

/**
 * Resultado de evaluar todas las alertas de un cliente contra el tipo de cambio vigente.
 *
 * Todas las alertas se evalúan contra el mismo dato, por lo que el dato y su calidad se expresan
 * una sola vez y no en cada alerta: `snapshot` es el valor usado con su procedencia y frescura, y
 * `basis` resume ambas en la única dimensión que un consumidor necesita para decidir si actúa. Un
 * consumidor que extraiga una alerta de la lista debe conservar la base junto con ella, igual que
 * conservaría el valor del tipo de cambio.
 *
 * Contiene todas las alertas del cliente con su resultado, no solo las disparadas: así "evaluada y
 * no disparada", "inactiva" y "de otra serie" se distinguen entre sí, y una lista vacía significa
 * inequívocamente que el cliente no tiene alertas.
 *
 * @param evaluatedAt
 *   instante en que se aplicó la regla; es también el "ahora" respecto al que se juzga la
 *   antigüedad del dato
 * @param snapshot
 *   tipo de cambio usado, con su procedencia y frescura
 * @param basis
 *   calidad del dato como fundamento para actuar, derivada del snapshot
 * @param alerts
 *   alertas del cliente en el orden del listado, cada una con su resultado
 */
final case class AlertEvaluation(
    evaluatedAt: Instant,
    snapshot: RateSnapshot,
    basis: EvaluationBasis,
    alerts: List[EvaluatedAlert]
):

  def triggered: List[EvaluatedAlert] = alerts.filter(_.outcome.triggered)

object AlertEvaluation:

  /**
   * Base de la evaluación que corresponde a un snapshot: la frescura decide si el dato está
   * confirmado y la procedencia si es oficial. Es el único punto donde [[Freshness]], un concepto
   * de la obtención del dato, se traduce al vocabulario del dominio.
   */
  def basisOf(snapshot: RateSnapshot): EvaluationBasis =
    EvaluationBasis.of(snapshot.rate, confirmed = snapshot.freshness == Freshness.Fresh)
