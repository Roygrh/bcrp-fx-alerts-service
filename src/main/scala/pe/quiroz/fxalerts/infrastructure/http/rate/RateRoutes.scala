package pe.quiroz.fxalerts.infrastructure.http.rate

import cats.Monad
import cats.effect.Clock
import cats.syntax.all.*
import pe.quiroz.fxalerts.application.rate.ExchangeRateService
import pe.quiroz.fxalerts.infrastructure.http.problem.ApiError
import sttp.tapir.server.ServerEndpoint

/**
 * Enlaza el endpoint de tipo de cambio con el servicio de aplicación.
 *
 * Solo traduce: el resultado del servicio a la representación pública (calculando la antigüedad en
 * el momento de responder) y los errores de dominio a errores HTTP mediante
 * [[ApiError.fromDomain]].
 */
final class RateRoutes[F[_]: Monad: Clock](service: ExchangeRateService[F]):

  val serverEndpoints: List[ServerEndpoint[Any, F]] = List(
    RateEndpoints.current.serverLogic { _ =>
      for
        result <- service.current
        now    <- Clock[F].realTimeInstant
      yield result.bimap(ApiError.fromDomain, RateResponse.from(_, now))
    }
  )
