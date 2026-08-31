package pe.quiroz.fxalerts.infrastructure.http.problem

import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.{Codec, Decoder, Encoder}
import sttp.model.{MediaType, StatusCode}
import sttp.tapir.*
import sttp.tapir.json.circe.*

/**
 * Error atribuible a un campo concreto de la petición.
 *
 * @param field
 *   nombre del campo tal como aparece en el contrato (`threshold`, `clientId`, `id`, `body`...)
 * @param message
 *   explicación legible del problema
 */
final case class FieldError(field: String, message: String) derives Codec.AsObject, Schema

/**
 * Cuerpo de error según Problem Details (RFC 7807).
 *
 * Es el único formato en que este servicio devuelve errores, con `content-type`
 * `application/problem+json`. Los campos `type`, `title`, `status` y `detail` son los definidos por
 * la RFC; `errors` es la extensión que detalla los problemas por campo en las validaciones y se
 * omite del JSON cuando no aplica.
 *
 * Ninguna fábrica de este objeto acepta texto procedente de excepciones o del driver de base de
 * datos: los detalles técnicos se registran en el log y el cliente solo recibe mensajes de negocio.
 *
 * @param type
 *   identificador estable del tipo de problema (URN); los clientes pueden discriminar por él
 * @param title
 *   resumen corto y fijo para cada tipo de problema
 * @param status
 *   código de estado HTTP, repetido en el cuerpo tal como pide la RFC
 * @param detail
 *   explicación específica de esta ocurrencia
 * @param errors
 *   errores por campo, solo en respuestas de validación
 */
final case class ProblemDetails(
    `type`: String,
    title: String,
    status: Int,
    detail: String,
    errors: Option[List[FieldError]]
) derives Schema

object ProblemDetails:

  /** Formato `application/problem+json` (RFC 7807) para los cuerpos de error. */
  case object ProblemJson extends CodecFormat:
    override val mediaType: MediaType = MediaType("application", "problem+json")

  given Encoder.AsObject[ProblemDetails] =
    deriveEncoder[ProblemDetails].mapJsonObject(_.filter { case (_, value) => !value.isNull })

  given Decoder[ProblemDetails] = deriveDecoder[ProblemDetails]

  /**
   * Cuerpo Tapir reutilizable por todas las salidas de error. Comparte el codec circe de
   * `jsonBody`, cambiando únicamente el tipo de medio declarado.
   */
  val body: EndpointIO.Body[String, ProblemDetails] =
    stringBodyUtf8AnyFormat(circeCodec[ProblemDetails].format(ProblemJson))

  // --- Catálogo de tipos de problema -----------------------------------------------------------
  //
  // Los identificadores son URN para no prometer una URL de documentación que todavía no existe;
  // si en el futuro se publica, basta con cambiar el prefijo sin romper a los clientes que ya
  // discriminan por el valor completo.

  private val typePrefix = "urn:fx-alerts:problem:"

  def notFound(detail: String): ProblemDetails =
    ProblemDetails(
      `type` = typePrefix + "not-found",
      title = "Recurso no encontrado",
      status = StatusCode.NotFound.code,
      detail = detail,
      errors = None
    )

  /** Uno o más campos no cumplen las reglas de negocio del dominio. */
  def validation(errors: List[FieldError]): ProblemDetails =
    ProblemDetails(
      `type` = typePrefix + "validation",
      title = "Petición inválida",
      status = StatusCode.BadRequest.code,
      detail = "Uno o más campos no cumplen las reglas de negocio",
      errors = Some(errors)
    )

  /** La petición no respeta el protocolo: JSON mal formado, tipos incorrectos, UUID inválido... */
  def malformedRequest(errors: List[FieldError]): ProblemDetails =
    ProblemDetails(
      `type` = typePrefix + "malformed-request",
      title = "Petición mal formada",
      status = StatusCode.BadRequest.code,
      detail = "La petición no tiene la forma esperada",
      errors = Some(errors)
    )

  val methodNotAllowed: ProblemDetails =
    ProblemDetails(
      `type` = typePrefix + "method-not-allowed",
      title = "Método no permitido",
      status = StatusCode.MethodNotAllowed.code,
      detail = "El método HTTP no está permitido para esta ruta",
      errors = None
    )

  /**
   * Una fuente externa de la que depende la operación no responde y no hay dato alternativo. El
   * detalle nombra la capacidad afectada, nunca el error técnico.
   */
  def sourceUnavailable(detail: String): ProblemDetails =
    ProblemDetails(
      `type` = typePrefix + "source-unavailable",
      title = "Fuente de datos no disponible",
      status = StatusCode.ServiceUnavailable.code,
      detail = detail,
      errors = None
    )

  /** Respuesta genérica para cualquier fallo no previsto; nunca incluye la causa. */
  val internal: ProblemDetails =
    ProblemDetails(
      `type` = typePrefix + "internal",
      title = "Error interno",
      status = StatusCode.InternalServerError.code,
      detail = "Se produjo un error inesperado al procesar la petición",
      errors = None
    )

  /** Comodín para códigos que Tapir puede producir sin un tipo de problema propio (p. ej. 415). */
  def forStatus(status: StatusCode): ProblemDetails =
    status match
      case StatusCode.NotFound         => notFound("No existe ningún recurso en la ruta indicada")
      case StatusCode.MethodNotAllowed => methodNotAllowed
      case StatusCode.InternalServerError => internal
      case other                          =>
        ProblemDetails(
          `type` = typePrefix + "request-rejected",
          title = "Petición rechazada",
          status = other.code,
          detail = "La petición no puede procesarse tal como se ha enviado",
          errors = None
        )

  val exampleNotFound: ProblemDetails =
    notFound("No existe la alerta con id 6f1c2a3e-9d4b-4c1a-8e2f-0b7d5a6c4e21")

  val exampleValidation: ProblemDetails =
    validation(
      List(FieldError("threshold", "El umbral debe ser estrictamente positivo (recibido: 0)"))
    )

  val exampleSourceUnavailable: ProblemDetails =
    sourceUnavailable(
      "El tipo de cambio de la serie PD04640PD no está disponible en este momento; " +
        "inténtelo más tarde"
    )
