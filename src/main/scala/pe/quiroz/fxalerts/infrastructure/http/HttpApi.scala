package pe.quiroz.fxalerts.infrastructure.http

import cats.effect.Async
import org.http4s.HttpApp
import pe.quiroz.fxalerts.infrastructure.http.health.HealthRoutes
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.http4s.Http4sServerInterpreter
import sttp.tapir.swagger.bundle.SwaggerInterpreter

/**
 * Compone los endpoints de la API y la documentación interactiva en una única `HttpApp`.
 *
 * Swagger UI se sirve en `/docs` y el documento OpenAPI en `/docs/docs.yaml`, ambos generados a
 * partir de las mismas definiciones Tapir que atienden las peticiones.
 */
object HttpApi:

  val apiTitle: String   = "BCRP FX Alerts Service"
  val apiVersion: String = "0.1.0"

  def httpApp[F[_]: Async](healthRoutes: HealthRoutes[F]): HttpApp[F] =
    val apiEndpoints: List[ServerEndpoint[Any, F]] = healthRoutes.serverEndpoints

    val docsEndpoints: List[ServerEndpoint[Any, F]] =
      SwaggerInterpreter().fromServerEndpoints[F](apiEndpoints, apiTitle, apiVersion)

    Http4sServerInterpreter[F]().toRoutes(apiEndpoints ++ docsEndpoints).orNotFound
