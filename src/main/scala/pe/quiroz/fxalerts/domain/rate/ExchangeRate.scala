package pe.quiroz.fxalerts.domain.rate

import pe.quiroz.fxalerts.domain.alert.BcrpSeries

import java.time.LocalDate

/**
 * Valor de tipo de cambio en una fecha concreta, con su procedencia.
 *
 * Es un hecho: una vez publicado, el trío (procedencia, fecha, valor) no cambia. La fecha es la del
 * dato según el calendario de referencia ([[PeruvianCalendar]]), no el momento en que se consultó;
 * esa distinción la aporta [[pe.quiroz.fxalerts.application.rate.RateSnapshot]].
 *
 * La procedencia forma parte del hecho y no de su recuperación: "3.52 según la SBS" y "3.35 según
 * un agregador de mercado" son medidas distintas de la misma magnitud, y toda regla de negocio que
 * consuma el valor (por ejemplo, decidir si una alerta se dispara sobre un dato no oficial)
 * necesita saberlo sin depender de cómo llegó el dato.
 *
 * @param series
 *   serie de referencia solicitada; el valor la representa o la aproxima según `provider`
 * @param date
 *   fecha del dato
 * @param value
 *   soles por dólar, con los decimales que entrega la fuente
 * @param provider
 *   quién produjo el valor, si es oficial y qué mide
 */
final case class ExchangeRate(
    series: BcrpSeries,
    date: LocalDate,
    value: BigDecimal,
    provider: RateProvider
):

  /** `true` si el valor es un precio oficial de referencia (ver [[RateProvider.official]]). */
  def official: Boolean = provider.official
