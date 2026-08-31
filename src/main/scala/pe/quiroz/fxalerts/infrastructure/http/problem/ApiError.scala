package pe.quiroz.fxalerts.infrastructure.http.problem

import pe.quiroz.fxalerts.domain.DomainError
import sttp.model.StatusCode
import sttp.tapir.*

/**
 * Errores que la API devuelve a través de la salida de error de los endpoints.
 *
 * Cada variante se corresponde con exactamente un código de estado y transporta el cuerpo Problem
 * Details ya construido. La jerarquía es sellada por dos motivos: [[ApiError.output]] declara una
 * variante `oneOf` por cada caso (la documentación OpenAPI y el serializador no pueden divergir) y
 * [[ApiError.fromDomain]] es la única traducción desde [[DomainError]], de modo que un error de
 * dominio nuevo produce un aviso de exhaustividad en un solo lugar.
 */
sealed trait ApiError extends Product with Serializable:
  def problem: ProblemDetails

object ApiError:

  final case class BadRequest(problem: ProblemDetails)         extends ApiError
  final case class NotFound(problem: ProblemDetails)           extends ApiError
  final case class ServiceUnavailable(problem: ProblemDetails) extends ApiError
  final case class Internal(problem: ProblemDetails)           extends ApiError

  /**
   * Punto único de traducción de errores de dominio a errores HTTP.
   *
   * Los mensajes provienen de `DomainError.message`, que se redacta para el consumidor y no
   * contiene información de infraestructura. Los errores de validación se atribuyen al campo del
   * contrato que los origina.
   */
  def fromDomain(error: DomainError): ApiError =
    error match
      case DomainError.AlertNotFound(_) =>
        NotFound(ProblemDetails.notFound(error.message))
      case DomainError.InvalidThreshold(_, _) =>
        BadRequest(ProblemDetails.validation(List(FieldError("threshold", error.message))))
      case DomainError.InvalidClientId(_, _) =>
        BadRequest(ProblemDetails.validation(List(FieldError("clientId", error.message))))
      case DomainError.ExchangeRateNotPublished(_) =>
        NotFound(ProblemDetails.notFound(error.message))
      // 503 y no 502/504: el cliente habla con este servicio, no con el BCRP, y este servicio no
      // actúa como pasarela transparente. 503 expresa "temporalmente no disponible, reintente"
      // sin revelar la topología aguas arriba, y es el mismo código que usa /health para DOWN.
      case DomainError.ExchangeRateUnavailable(_) =>
        ServiceUnavailable(ProblemDetails.sourceUnavailable(error.message))

  /**
   * Salida de error compartida por todos los endpoints de la API.
   *
   * El 500 no lo produce ninguna lógica de servidor (lo emite el manejador de excepciones de
   * [[ProblemHandlers]]); se declara aquí para que figure en la documentación con el mismo formato.
   */
  val output: EndpointOutput[ApiError] =
    oneOf[ApiError](
      oneOfVariant(
        StatusCode.BadRequest,
        ProblemDetails.body
          .description("La petición es inválida; `errors` detalla el problema de cada campo")
          .example(ProblemDetails.exampleValidation)
          .map(BadRequest.apply)(_.problem)
      ),
      oneOfVariant(
        StatusCode.NotFound,
        ProblemDetails.body
          .description("El recurso no existe (o no hay dato publicado para él)")
          .example(ProblemDetails.exampleNotFound)
          .map(NotFound.apply)(_.problem)
      ),
      oneOfVariant(
        StatusCode.ServiceUnavailable,
        ProblemDetails.body
          .description("Una fuente externa no responde y no hay dato en caché; reintente más tarde")
          .example(ProblemDetails.exampleSourceUnavailable)
          .map(ServiceUnavailable.apply)(_.problem)
      ),
      oneOfVariant(
        StatusCode.InternalServerError,
        ProblemDetails.body
          .description("Error no previsto; el detalle queda registrado en el servidor")
          .example(ProblemDetails.internal)
          .map(Internal.apply)(_.problem)
      )
    )
