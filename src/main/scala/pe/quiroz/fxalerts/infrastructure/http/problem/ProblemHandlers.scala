package pe.quiroz.fxalerts.infrastructure.http.problem

import org.typelevel.log4cats.Logger
import pe.quiroz.fxalerts.infrastructure.http.middleware.RequestLogging
import sttp.model.{Header, StatusCode}
import sttp.monad.MonadError
import sttp.tapir.*
import sttp.tapir.DecodeResult.Error.JsonDecodeException
import sttp.tapir.server.interceptor.DecodeFailureContext
import sttp.tapir.server.interceptor.decodefailure.{
  DecodeFailureHandler,
  DefaultDecodeFailureHandler
}
import sttp.tapir.server.interceptor.exception.{ExceptionContext, ExceptionHandler}
import sttp.tapir.server.interceptor.reject.{DefaultRejectHandler, RejectHandler}
import sttp.tapir.server.model.ValuedEndpointOutput

/**
 * Manejadores de Tapir que cubren los errores producidos fuera de la lógica de negocio, para que
 * toda respuesta de error del servicio use Problem Details:
 *
 *   - fallos al decodificar la petición (JSON mal formado, UUID inválido, parámetro ausente...)
 *   - excepciones no controladas en la lógica de servidor
 *   - rutas inexistentes o métodos no permitidos
 */
object ProblemHandlers:

  private val problemOutput: EndpointOutput[(StatusCode, List[Header], ProblemDetails)] =
    statusCode.and(headers).and(ProblemDetails.body)

  private def respond(
      status: StatusCode,
      extraHeaders: List[Header],
      problem: ProblemDetails
  ): ValuedEndpointOutput[?] =
    ValuedEndpointOutput(problemOutput, (status, extraHeaders, problem))

  /**
   * Conserva la decisión por defecto de Tapir sobre qué fallos se responden y con qué código (un
   * `path[UUID]` inválido es 400; una ruta que no coincide se cede al siguiente endpoint) y
   * reemplaza únicamente el cuerpo de la respuesta.
   */
  def decodeFailureHandler[F[_]]: DecodeFailureHandler[F] =
    DecodeFailureHandler.pure[F] { ctx =>
      DefaultDecodeFailureHandler.respond(ctx).map { case (status, extraHeaders) =>
        val problem =
          if status == StatusCode.BadRequest then ProblemDetails.malformedRequest(fieldErrors(ctx))
          else ProblemDetails.forStatus(status)
        respond(status, extraHeaders, problem)
      }
    }

  /**
   * Registra la excepción con el identificador de correlación y responde un 500 genérico. Es el
   * único lugar donde el detalle técnico de un fallo inesperado toca el log.
   */
  def exceptionHandler[F[_]: Logger]: ExceptionHandler[F] =
    new ExceptionHandler[F]:
      def apply(ctx: ExceptionContext)(using
          monad: MonadError[F]
      ): F[Option[ValuedEndpointOutput[?]]] =
        val requestId = ctx.request.header(RequestLogging.requestIdHeader.toString).getOrElse("-")
        val path      = ctx.request.pathSegments.mkString("/", "/", "")
        monad.map(
          Logger[F].error(ctx.e)(
            s"Error inesperado atendiendo ${ctx.request.method} $path requestId=$requestId"
          )
        )(_ => Some(respond(StatusCode.InternalServerError, Nil, ProblemDetails.internal)))

  /** 405 si alguna ruta coincide pero no el método; 404 en cualquier otro caso. */
  def rejectHandler[F[_]]: RejectHandler[F] =
    DefaultRejectHandler[F](
      (status, _) => respond(status, Nil, ProblemDetails.forStatus(status)),
      Some((StatusCode.NotFound, ""))
    )

  // --- Traducción de fallos de decodificación a errores por campo -------------------------------

  private def fieldErrors(ctx: DecodeFailureContext): List[FieldError] =
    (ctx.failingInput, ctx.failure) match
      case (_: EndpointIO.Body[?, ?], DecodeResult.Error(_, json: JsonDecodeException))
          if json.errors.nonEmpty =>
        json.errors.map(error => FieldError(jsonPath(error.path), error.msg))
      case (input, failure) =>
        List(FieldError(inputName(input), failureMessage(input, failure)))

  private def jsonPath(path: List[FieldName]): String =
    if path.isEmpty then "body" else path.map(_.encodedName).mkString(".")

  private def inputName(input: EndpointInput[?]): String =
    input match
      case capture: EndpointInput.PathCapture[?] => capture.name.getOrElse("path")
      case query: EndpointInput.Query[?]         => query.name
      case header: EndpointIO.Header[?]          => header.name
      case _: EndpointIO.Body[?, ?]              => "body"
      case _                                     => "request"

  private def failureMessage(input: EndpointInput[?], failure: DecodeResult.Failure): String =
    failure match
      case DecodeResult.Missing         => "Falta un valor obligatorio"
      case DecodeResult.Multiple(_)     => "Se recibieron varios valores y solo se admite uno"
      case DecodeResult.InvalidValue(_) => "El valor no cumple las restricciones declaradas"
      case _: DecodeResult.Mismatch | _: DecodeResult.Error =>
        expectedFormat(input).fold("Valor inválido")(format =>
          s"Valor inválido; se esperaba $format"
        )

  private def expectedFormat(input: EndpointInput[?]): Option[String] =
    input match
      case capture: EndpointInput.PathCapture[?] => capture.codec.schema.format
      case query: EndpointInput.Query[?]         => query.codec.schema.format
      case header: EndpointIO.Header[?]          => header.codec.schema.format
      case _                                     => None
