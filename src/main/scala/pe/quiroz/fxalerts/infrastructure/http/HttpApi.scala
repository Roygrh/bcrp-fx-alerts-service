package pe.quiroz.fxalerts.infrastructure.http

import cats.effect.Async
import org.http4s.HttpApp
import org.typelevel.log4cats.Logger
import pe.quiroz.fxalerts.infrastructure.http.alert.AlertRoutes
import pe.quiroz.fxalerts.infrastructure.http.health.HealthRoutes
import pe.quiroz.fxalerts.infrastructure.http.middleware.RequestLogging
import pe.quiroz.fxalerts.infrastructure.http.problem.ProblemHandlers
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.http4s.{Http4sServerInterpreter, Http4sServerOptions}
import sttp.tapir.swagger.bundle.SwaggerInterpreter

/**
 * Compone los endpoints de la API y la documentación interactiva en una única `HttpApp`.
 *
 * Swagger UI se sirve en `/docs` y el documento OpenAPI en `/docs/docs.yaml`, ambos generados a
 * partir de las mismas definiciones Tapir que atienden las peticiones. Toda la aplicación queda
 * envuelta por el middleware de observabilidad ([[RequestLogging]]) y los errores ajenos a la
 * lógica de negocio se responden como Problem Details a través de [[ProblemHandlers]].
 */
object HttpApi:

  val apiTitle: String   = "BCRP FX Alerts Service"
  val apiVersion: String = "0.1.0"

  def httpApp[F[_]: Async: Logger](
      healthRoutes: HealthRoutes[F],
      alertRoutes: AlertRoutes[F]
  ): HttpApp[F] =
    fromEndpoints(healthRoutes.serverEndpoints ++ alertRoutes.serverEndpoints)

  /**
   * Construye la aplicación a partir de un conjunto arbitrario de endpoints. Las suites de pruebas
   * lo usan para montar un grupo de rutas aislado con la misma configuración que producción
   * (manejadores de error y middleware incluidos).
   */
  def fromEndpoints[F[_]: Async: Logger](apiEndpoints: List[ServerEndpoint[Any, F]]): HttpApp[F] =
    val docsEndpoints: List[ServerEndpoint[Any, F]] =
      SwaggerInterpreter().fromServerEndpoints[F](apiEndpoints, apiTitle, apiVersion)

    val routes =
      Http4sServerInterpreter[F](serverOptions[F]).toRoutes(apiEndpoints ++ docsEndpoints)
    RequestLogging(routes.orNotFound)

  /**
   * Opciones del intérprete: decodificación fallida, excepciones y rutas sin coincidencia responden
   * Problem Details. El log por petición corre a cargo de [[RequestLogging]], por lo que el log
   * propio de Tapir se desactiva para no duplicar líneas.
   */
  private def serverOptions[F[_]: Async: Logger]: Http4sServerOptions[F] =
    Http4sServerOptions
      .customiseInterceptors[F]
      .decodeFailureHandler(ProblemHandlers.decodeFailureHandler[F])
      .exceptionHandler(ProblemHandlers.exceptionHandler[F])
      .rejectHandler(ProblemHandlers.rejectHandler[F])
      .serverLog(None)
      .options
