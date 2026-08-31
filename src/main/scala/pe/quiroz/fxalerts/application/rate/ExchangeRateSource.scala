package pe.quiroz.fxalerts.application.rate

import pe.quiroz.fxalerts.domain.DomainError.ExchangeRateError
import pe.quiroz.fxalerts.domain.alert.BcrpSeries
import pe.quiroz.fxalerts.domain.rate.ExchangeRate

import java.time.Instant

/**
 * Grado de confianza en la vigencia de un dato de tipo de cambio.
 *
 *   - `Fresh`: la fuente lo confirmó dentro del periodo de validez configurado.
 *   - `Stale`: la fuente no pudo confirmarlo (no responde) y se entrega el último valor conocido,
 *     fuera de su periodo de validez. El consumidor decide si le sirve; para una serie estadística
 *     cuyo dato del día no cambia, suele ser preferible a no tener nada.
 */
enum Freshness:
  case Fresh, Stale

/**
 * Tipo de cambio junto con la información necesaria para juzgar su vigencia.
 *
 * El negocio no necesita saber si el dato salió de la fuente o de una caché intermedia: le basta
 * con la fecha del dato ([[ExchangeRate.date]]), el instante en que la fuente lo entregó
 * (`retrievedAt`, del que se deriva la antigüedad) y la [[Freshness]].
 */
final case class RateSnapshot(rate: ExchangeRate, retrievedAt: Instant, freshness: Freshness)

/**
 * Puerto de salida: obtención del tipo de cambio vigente de una serie.
 *
 * La firma responde a la pregunta del negocio ("¿cuál es el último tipo de cambio publicado?") y no
 * a la forma de la API del BCRP: el rango de fechas consultado, el formato de la respuesta y el
 * tratamiento de los días sin dato son detalles del adaptador.
 *
 * Los dos resultados esperables distintos del éxito son valores, no excepciones:
 *   - `Left(ExchangeRateNotPublished)`: la fuente respondió pero no hay ningún valor publicado en
 *     la ventana consultada (por ejemplo, todos los días son "n.d.").
 *   - `Left(ExchangeRateUnavailable)`: la fuente no pudo consultarse (tiempo de espera agotado,
 *     error del servidor, respuesta ininteligible) y no existe ningún valor que ofrecer.
 *
 * Una implementación decorada con caché puede responder `Right` con `Freshness.Stale` donde la
 * fuente cruda respondería `Left(ExchangeRateUnavailable)`.
 */
trait ExchangeRateSource[F[_]]:

  /** Último tipo de cambio publicado para la serie. */
  def latest(series: BcrpSeries): F[Either[ExchangeRateError, RateSnapshot]]
