package pe.quiroz.fxalerts.infrastructure.cache

import cats.effect.std.Supervisor
import cats.effect.syntax.all.*
import cats.effect.{Deferred, Ref, Resource, Temporal}
import cats.syntax.all.*
import org.typelevel.log4cats.Logger
import pe.quiroz.fxalerts.application.rate.{ExchangeRateSource, Freshness, RateSnapshot}
import pe.quiroz.fxalerts.domain.DomainError.{
  ExchangeRateError,
  ExchangeRateNotPublished,
  ExchangeRateUnavailable
}
import pe.quiroz.fxalerts.domain.alert.BcrpSeries
import pe.quiroz.fxalerts.infrastructure.config.RateCacheConfig

import java.time.{Duration as JavaDuration, Instant}
import scala.concurrent.duration.FiniteDuration

/**
 * Decorador de [[ExchangeRateSource]] que añade una caché en memoria por serie.
 *
 * Reglas:
 *
 *   - Un valor obtenido de la fuente se sirve sin reconsultarla durante `ttl`, marcado `Fresh`.
 *   - Cuando vence, la primera petición que llega reconsulta la fuente y las demás esperan ese
 *     mismo resultado (una sola llamada en vuelo por serie, también en el arranque con la caché
 *     vacía).
 *   - Si la fuente falla y existe un valor anterior con antigüedad menor que `maxStale`, se sirve
 *     ese valor marcado `Stale` y se deja de reconsultar la fuente durante `failureBackoff`, para
 *     que cada petición no pague el coste de los reintentos mientras la fuente siga caída. Servir
 *     un dato obsoleto es preferible a no servir nada porque el dato del día no cambia una vez
 *     publicado; el riesgo (que exista un dato más nuevo que no vemos) queda expuesto al consumidor
 *     a través de `Freshness` y `retrievedAt`.
 *   - Si la fuente responde que no hay dato publicado en la ventana, se respeta esa respuesta
 *     aunque haya un valor anterior: la fuente está operativa y es la autoridad.
 *   - Si la fuente falla y no hay valor anterior (o es más viejo que `maxStale`), el resultado es
 *     `ExchangeRateUnavailable`, y ese fallo se retiene durante `failureBackoff`: las peticiones
 *     siguientes fallan de inmediato en lugar de repetir la ronda de reintentos contra una fuente
 *     que acaba de no responder.
 *
 * La consulta a la fuente se ejecuta en un fibra supervisada e independiente de quien la provocó:
 * si ese llamador se cancela (por ejemplo, un tiempo límite en la verificación de salud), la
 * consulta continúa y su resultado sigue alimentando la caché y al resto de esperas.
 */
final class CachedExchangeRateSource[F[_]: Temporal: Logger] private (
    underlying: ExchangeRateSource[F],
    config: RateCacheConfig,
    entries: Ref[F, Map[BcrpSeries, CachedExchangeRateSource.Entry[F]]],
    supervisor: Supervisor[F]
) extends ExchangeRateSource[F]:

  import CachedExchangeRateSource.*

  def latest(series: BcrpSeries): F[Either[ExchangeRateError, RateSnapshot]] =
    for
      now     <- Temporal[F].realTimeInstant
      pending <- Deferred[F, Either[ExchangeRateError, RateSnapshot]]
      action  <- entries.modify { map =>
        map.get(series) match
          case Some(Entry.Cached(snapshot, revalidateAt)) if now.isBefore(revalidateAt) =>
            (map, Action.Hit(snapshot))
          case Some(Entry.Refreshing(inFlight)) =>
            (map, Action.Await(inFlight))
          case Some(Entry.Failed(error, retryAt)) if now.isBefore(retryAt) =>
            (map, Action.Fail(error))
          case existing =>
            val previous = existing.collect { case Entry.Cached(snapshot, _) => snapshot }
            (map.updated(series, Entry.Refreshing(pending)), Action.Lead(previous))
      }
      result <- action match
        case Action.Hit(snapshot) =>
          val freshness =
            if age(snapshot, now) <= config.ttl then Freshness.Fresh else Freshness.Stale
          Logger[F]
            .debug(s"Caché de tipo de cambio: acierto para ${series.code} ($freshness)")
            .as(Right(snapshot.copy(freshness = freshness)))
        case Action.Await(inFlight) =>
          inFlight.get
        case Action.Fail(error) =>
          Logger[F]
            .debug(s"Caché de tipo de cambio: fallo reciente retenido para ${series.code}")
            .as(Left(error))
        case Action.Lead(previous) =>
          supervisor.supervise(refresh(series, previous, pending)) *> pending.get
    yield result

  private def refresh(
      series: BcrpSeries,
      previous: Option[RateSnapshot],
      pending: Deferred[F, Either[ExchangeRateError, RateSnapshot]]
  ): F[Unit] =
    val attempt = underlying.latest(series).attempt.flatMap { outcome =>
      Temporal[F].realTimeInstant.flatMap { now =>
        outcome match
          case Right(Right(snapshot)) =>
            val stored = snapshot.copy(freshness = Freshness.Fresh)
            entries.update(
              _.updated(series, Entry.Cached(stored, now.plusNanos(config.ttl.toNanos)))
            )
              *> pending.complete(Right(stored)).void
          case Right(Left(error: ExchangeRateNotPublished)) =>
            restore(series, previous) *> pending.complete(Left(error)).void
          case Right(Left(error: ExchangeRateUnavailable)) =>
            serveStaleOrFail(series, previous, error, now, pending)
          case Left(throwable) =>
            Logger[F].error(throwable)(
              s"La fuente del tipo de cambio lanzó una excepción para ${series.code}"
            ) *> serveStaleOrFail(series, previous, ExchangeRateUnavailable(series), now, pending)
      }
    }
    attempt.onCancel(
      restore(series, previous) *> pending.complete(Left(ExchangeRateUnavailable(series))).void
    )

  private def serveStaleOrFail(
      series: BcrpSeries,
      previous: Option[RateSnapshot],
      error: ExchangeRateUnavailable,
      now: Instant,
      pending: Deferred[F, Either[ExchangeRateError, RateSnapshot]]
  ): F[Unit] =
    previous match
      case Some(snapshot) if age(snapshot, now) <= config.maxStale =>
        val revalidateAt = now.plusNanos(config.failureBackoff.toNanos)
        Logger[F].warn(
          s"La fuente del tipo de cambio no responde para ${series.code}; se sirve el último dato " +
            s"conocido (${snapshot.rate.date}, obtenido en ${snapshot.retrievedAt}) durante " +
            s"${config.failureBackoff.toSeconds} s antes de volver a intentarlo"
        ) *> entries.update(_.updated(series, Entry.Cached(snapshot, revalidateAt)))
          *> pending.complete(Right(snapshot.copy(freshness = Freshness.Stale))).void
      case _ =>
        val retryAt = now.plusNanos(config.failureBackoff.toNanos)
        entries.update(_.updated(series, Entry.Failed(error, retryAt)))
          *> pending.complete(Left(error)).void

  /** Deja la entrada como estaba antes de la consulta (vencida, para que la próxima reconsulte). */
  private def restore(series: BcrpSeries, previous: Option[RateSnapshot]): F[Unit] =
    entries.update { map =>
      previous match
        case Some(snapshot) => map.updated(series, Entry.Cached(snapshot, Instant.MIN))
        case None           => map - series
    }

  private def age(snapshot: RateSnapshot, now: Instant): FiniteDuration =
    FiniteDuration(JavaDuration.between(snapshot.retrievedAt, now).toNanos, "ns")

object CachedExchangeRateSource:

  /**
   * Estado de una serie en la caché.
   *
   *   - `Cached`: valor conocido y el instante a partir del cual debe reconsultarse la fuente.
   *   - `Refreshing`: hay una consulta en vuelo; los llamadores esperan su resultado.
   *   - `Failed`: la fuente falló sin valor que ofrecer; hasta `retryAt` se responde ese fallo.
   */
  private[cache] enum Entry[F[_]]:
    case Cached(snapshot: RateSnapshot, revalidateAt: Instant)
    case Refreshing(inFlight: Deferred[F, Either[ExchangeRateError, RateSnapshot]])
    case Failed(error: ExchangeRateUnavailable, retryAt: Instant)

  private enum Action[F[_]]:
    case Hit(snapshot: RateSnapshot)
    case Await(inFlight: Deferred[F, Either[ExchangeRateError, RateSnapshot]])
    case Fail(error: ExchangeRateUnavailable)
    case Lead(previous: Option[RateSnapshot])

  def resource[F[_]: Temporal: Logger](
      underlying: ExchangeRateSource[F],
      config: RateCacheConfig
  ): Resource[F, CachedExchangeRateSource[F]] =
    for
      supervisor <- Supervisor[F](await = false)
      entries    <- Resource.eval(Ref.of[F, Map[BcrpSeries, Entry[F]]](Map.empty))
    yield new CachedExchangeRateSource[F](underlying, config, entries, supervisor)
