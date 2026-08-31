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
        "Devuelve el estado agregado del servicio y de sus componentes. Responde 200 tanto con " +
          "`UP` como con `DEGRADED` (el servicio sigue atendiendo, aunque con capacidad mermada: " +
          "tipo de cambio servido desde caché o desde la fuente de respaldo no oficial) y 503 con " +
          "`DOWN` (la base de datos no responde). `rates.source` y `rates.official` indican qué " +
          "fuente está sirviendo el tipo de cambio. Un reinicio solo procede ante `DOWN`."
      )
      .tag("Operación")
      .out(
        statusCode(StatusCode.Ok)
          .and(
            jsonBody[HealthResponse]
              .examples(
                List(
                  EndpointIO.Example.of(HealthResponse.exampleUp, name = Some("Operativo")),
                  EndpointIO.Example.of(HealthResponse.exampleDegraded, name = Some("Degradado"))
                )
              )
          )
      )
      .errorOut(
        statusCode(StatusCode.ServiceUnavailable)
          .and(jsonBody[HealthResponse].example(HealthResponse.exampleDown))
      )
