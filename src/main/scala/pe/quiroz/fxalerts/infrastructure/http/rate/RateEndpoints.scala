package pe.quiroz.fxalerts.infrastructure.http.rate

import pe.quiroz.fxalerts.application.security.Scope
import pe.quiroz.fxalerts.infrastructure.http.auth.SecuredEndpoint
import pe.quiroz.fxalerts.infrastructure.http.problem.ApiError
import sttp.tapir.*
import sttp.tapir.json.circe.*

/**
 * Definición declarativa del recurso de tipo de cambio bajo `/api/v1/rates`.
 *
 * `current` es un recurso de solo lectura que representa "el último valor publicado" de la serie de
 * referencia; por eso no recibe parámetros. Exige el alcance `rates:read`. La salida de error común
 * ([[ApiError.output]]) documenta el 404 (sin dato publicado en la ventana consultada) y el 503
 * (ninguna fuente accesible y sin dato en caché) en formato Problem Details.
 */
object RateEndpoints:

  val current: SecuredEndpoint[Unit, RateResponse] =
    SecuredEndpoint(Scope.RatesRead)(
      endpoint.get
        .in("api" / "v1" / "rates" / "current")
        .tag("Tipo de cambio")
        .summary("Tipo de cambio vigente")
        .description(
          "Devuelve el último tipo de cambio disponible para la serie de referencia (PD04640PD, " +
            "TC venta del sistema bancario SBS, soles por dólar). `source` identifica la fuente " +
            "que entregó el dato: `official = true` es el precio oficial publicado por la SBS vía " +
            "BCRP; `official = false` es una referencia de mercado del respaldo " +
            "(ExchangeRate-API), que se usa solo cuando la fuente oficial no responde. " +
            "`freshness` indica si la fuente confirmó el dato dentro del periodo de validez " +
            "(`FRESH`) o si se entrega el último valor conocido porque ninguna fuente responde " +
            "(`STALE`); `retrievedAt` y `ageSeconds` permiten juzgar su antigüedad. Responde 404 " +
            "si no hay dato publicado en la ventana consultada y 503 si ninguna fuente responde y " +
            "no existe ningún valor en caché."
        )
        .errorOut(ApiError.output)
        .out(
          jsonBody[RateResponse].examples(
            List(
              EndpointIO.Example.of(RateResponse.example, name = Some("Fuente oficial (BCRP)")),
              EndpointIO.Example.of(
                RateResponse.exampleFallback,
                name = Some("Fuente de respaldo (no oficial)")
              )
            )
          )
        )
    )
