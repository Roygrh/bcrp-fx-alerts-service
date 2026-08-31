package pe.quiroz.fxalerts.application.rate

import pe.quiroz.fxalerts.domain.DomainError.ExchangeRateError
import pe.quiroz.fxalerts.domain.alert.BcrpSeries

/**
 * Servicio de aplicación para la consulta del tipo de cambio.
 *
 * Fija la serie de referencia del servicio (tipo de cambio venta SBS) y delega en el puerto
 * [[ExchangeRateSource]]. Es deliberadamente delgado: la política de caché y de reintentos vive en
 * los adaptadores que implementan el puerto, y la evaluación de alertas contra este valor queda
 * fuera de este servicio.
 */
final class ExchangeRateService[F[_]](source: ExchangeRateSource[F]):

  /** Serie de referencia del servicio. */
  val referenceSeries: BcrpSeries = BcrpSeries.UsdPenSbsSell

  /** Tipo de cambio vigente de la serie de referencia. */
  def current: F[Either[ExchangeRateError, RateSnapshot]] =
    source.latest(referenceSeries)
