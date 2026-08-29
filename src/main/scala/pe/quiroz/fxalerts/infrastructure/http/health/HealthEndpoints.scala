package pe.quiroz.fxalerts.infrastructure.http.health

import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.json.circe.*

/**
 * Definición declarativa del endpoint de salud.
 *
 * El endpoint es un valor puro: de esta única definición se derivan las rutas http4s y la
 * documentación OpenAPI, por lo que ambas no pueden divergir.
 */
object HealthEndpoints:

  val health: PublicEndpoint[Unit, HealthResponse, HealthResponse, Any] =
    endpoint.get
      .in("health")
      .summary("Estado del servicio")
      .description(
        "Devuelve el estado agregado del servicio y de sus dependencias. " +
          "Responde 200 cuando todos los componentes están operativos y 503 en caso contrario."
      )
      .tag("Operación")
      .out(
        statusCode(StatusCode.Ok)
          .and(jsonBody[HealthResponse].example(HealthResponse.exampleUp))
      )
      .errorOut(
        statusCode(StatusCode.ServiceUnavailable)
          .and(jsonBody[HealthResponse].example(HealthResponse.exampleDown))
      )
