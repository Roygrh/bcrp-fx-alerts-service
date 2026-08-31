package pe.quiroz.fxalerts.infrastructure.rate

import cats.Monad
import cats.data.NonEmptyList
import cats.syntax.all.*
import org.typelevel.log4cats.Logger
import pe.quiroz.fxalerts.application.rate.{ExchangeRateSource, RateSnapshot}
import pe.quiroz.fxalerts.domain.DomainError.{ExchangeRateError, ExchangeRateUnavailable}
import pe.quiroz.fxalerts.domain.alert.BcrpSeries
import pe.quiroz.fxalerts.domain.rate.RateProvider

/** Una fuente de tipo de cambio junto con la procedencia de los datos que entrega. */
final case class ProviderSource[F[_]](provider: RateProvider, source: ExchangeRateSource[F])

/**
 * Compositor de fuentes: implementa [[ExchangeRateSource]] consultando una lista ordenada de
 * fuentes hasta que una responde.
 *
 * Reglas:
 *   - Solo se pasa a la siguiente fuente cuando la actual **no pudo responder**
 *     (`ExchangeRateUnavailable`).
 *   - Si una fuente responde de forma autoritativa que no hay dato publicado en la ventana
 *     (`ExchangeRateNotPublished`), esa respuesta se respeta y no se consulta la siguiente: la
 *     fuente está operativa y es la autoridad sobre su serie.
 *   - Si todas fallan, el resultado es `ExchangeRateUnavailable`.
 *
 * Cada salto a una fuente de respaldo se registra con nivel de aviso, señalando si la fuente a la
 * que se recurre es o no oficial: que el servicio esté operando sobre una referencia de mercado en
 * lugar del precio oficial es un hecho que un operador debe poder ver en el log.
 */
final class FallbackExchangeRateSource[F[_]: Monad: Logger](
    sources: NonEmptyList[ProviderSource[F]]
) extends ExchangeRateSource[F]:

  def latest(series: BcrpSeries): F[Either[ExchangeRateError, RateSnapshot]] =
    consult(sources.head, sources.tail, series)

  private def consult(
      current: ProviderSource[F],
      remaining: List[ProviderSource[F]],
      series: BcrpSeries
  ): F[Either[ExchangeRateError, RateSnapshot]] =
    current.source.latest(series).flatMap {
      case Left(ExchangeRateUnavailable(_)) =>
        remaining match
          case next :: rest =>
            val kind = if next.provider.official then "oficial" else "NO oficial"
            Logger[F].warn(
              s"Fuente ${current.provider.code} no disponible para ${series.code}; " +
                s"se recurre a la fuente de respaldo ${next.provider.code} ($kind)"
            ) *> consult(next, rest, series)
          case Nil =>
            Logger[F]
              .error(
                s"Ninguna fuente de tipo de cambio pudo responder para ${series.code} " +
                  s"(orden consultado: ${sources.map(_.provider.code).toList.mkString(" > ")})"
              )
              .as(Left(ExchangeRateUnavailable(series)))
      case authoritative =>
        authoritative.pure[F]
    }
