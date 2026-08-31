package pe.quiroz.fxalerts.application.alert

import cats.Monad
import cats.data.EitherT
import cats.effect.Clock
import pe.quiroz.fxalerts.application.rate.ExchangeRateService
import pe.quiroz.fxalerts.domain.DomainError.ExchangeRateError
import pe.quiroz.fxalerts.domain.alert.ClientId

/**
 * Servicio de aplicación que evalúa las alertas de un cliente contra el tipo de cambio vigente.
 *
 * Orquesta los dos puertos y delega la regla en el dominio
 * ([[pe.quiroz.fxalerts.domain.alert.Alert.evaluate]]): obtiene el tipo de cambio de la serie de
 * referencia, lee las alertas del cliente y aplica la regla a cada una. No persiste nada ni
 * notifica a nadie: la evaluación es una consulta que se recalcula en cada llamada.
 *
 * Sin tipo de cambio no hay evaluación posible, ni siquiera vacía: el resultado incluye el dato
 * usado, y un consumidor no debe recibir "ninguna alerta disparada" cuando lo que ocurre es que no
 * hay dato contra el que evaluar. Por eso el dato se obtiene antes que las alertas y su ausencia se
 * devuelve tal cual ([[ExchangeRateError]]), con la misma traducción HTTP que la consulta directa
 * del tipo de cambio.
 *
 * Solo se evalúan las alertas del cliente indicado (`owner`), la identidad autenticada por la capa
 * de entrada: el acotamiento lo aplica el repositorio en la propia consulta, igual que en el resto
 * de operaciones.
 */
final class AlertEvaluationService[F[_]: Monad: Clock](
    repository: AlertRepository[F],
    rates: ExchangeRateService[F]
):

  def evaluate(owner: ClientId): F[Either[ExchangeRateError, AlertEvaluation]] =
    val evaluation = for
      snapshot <- EitherT(rates.current)
      alerts   <- EitherT.liftF(repository.findAll(owner))
      now      <- EitherT.liftF(Clock[F].realTimeInstant)
    yield AlertEvaluation(
      evaluatedAt = now,
      snapshot = snapshot,
      basis = AlertEvaluation.basisOf(snapshot),
      alerts = alerts.map(alert => EvaluatedAlert(alert, alert.evaluate(snapshot.rate)))
    )
    evaluation.value
