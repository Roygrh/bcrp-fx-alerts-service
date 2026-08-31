package pe.quiroz.fxalerts.infrastructure.remote

import cats.effect.Temporal
import cats.effect.syntax.all.*
import cats.syntax.all.*
import org.http4s.client.Client
import org.http4s.headers.Accept
import org.http4s.{MediaType, Method, Request, Status, Uri}
import org.typelevel.log4cats.Logger
import pe.quiroz.fxalerts.infrastructure.config.RemoteCallConfig

import java.io.IOException
import java.nio.channels.UnresolvedAddressException
import java.util.concurrent.TimeoutException
import scala.concurrent.duration.FiniteDuration

/**
 * Motivos por los que un intento contra un servicio remoto no produce resultado. `transient` decide
 * si merece reintento.
 */
enum RemoteFailure(val transient: Boolean):
  case Timeout                            extends RemoteFailure(transient = true)
  case ConnectionFailed(cause: Throwable) extends RemoteFailure(transient = true)
  case ServerError(status: Status)        extends RemoteFailure(transient = true)
  case RejectedRequest(status: Status)    extends RemoteFailure(transient = false)

  /** Respuesta 2xx cuyo cuerpo no tiene la forma esperada (JSON inválido, campos ausentes...). */
  case UnexpectedPayload(reason: String, bodyLength: Int) extends RemoteFailure(transient = false)

  /** El proveedor respondió con un error de aplicación explícito (aunque el HTTP fuera 200). */
  case ProviderError(reason: String) extends RemoteFailure(transient = false)

  case Unexpected(cause: Throwable) extends RemoteFailure(transient = false)

  def describe: String = this match
    case Timeout                 => "tiempo de espera agotado"
    case ConnectionFailed(cause) => s"fallo de conexión: ${cause.getClass.getSimpleName}"
    case ServerError(status)     => s"respuesta ${status.code}"
    case RejectedRequest(status) => s"respuesta ${status.code}, no se reintenta"
    case UnexpectedPayload(reason, bodyLength) =>
      s"cuerpo no interpretable ($bodyLength bytes): $reason"
    case ProviderError(reason) => s"error del proveedor: $reason, no se reintenta"
    case Unexpected(cause)     => s"error inesperado: ${cause.getClass.getSimpleName}"

/**
 * Política común de llamada a servicios remotos, compartida por todos los adaptadores HTTP.
 *
 * Por cada petición `GET`:
 *   - aplica un presupuesto de tiempo por intento que cubre conexión, cabeceras y cuerpo
 *     ([[RemoteCallConfig.attemptTimeout]]), de modo que un servidor que acepta la conexión y no
 *     responde también cuenta como tiempo de espera agotado;
 *   - clasifica el resultado en un [[RemoteFailure]] o delega en `interpret` la lectura del cuerpo
 *     de una respuesta 2xx;
 *   - reintenta solo ante fallos transitorios (tiempo de espera, fallo de conexión, 5xx), hasta
 *     `maxRetries` veces con espera que se duplica en cada reintento. Un 4xx, un cuerpo
 *     ininteligible o un error de aplicación del proveedor no se reintentan: repetir la petición no
 *     los arreglará y solo añadiría carga.
 *
 * Registro: una línea `INFO` por llamada completada con éxito, `WARN` por cada intento fallido que
 * se va a reintentar y `ERROR` cuando se agotan los intentos o el fallo no es recuperable. Nunca se
 * vuelca el cuerpo de la respuesta al log.
 */
final class RemoteCall[F[_]: Temporal: Logger](client: Client[F], config: RemoteCallConfig):

  /**
   * @param label
   *   identifica la llamada en el log (servicio y recurso), sin datos sensibles
   * @param interpret
   *   lectura del cuerpo de una respuesta 2xx; un `Left` se trata como fallo no transitorio
   * @param describe
   *   resumen del resultado para la línea de éxito del log
   */
  def get[A](label: String, uri: Uri)(interpret: String => Either[RemoteFailure, A])(
      describe: A => String
  ): F[Either[RemoteFailure, A]] =
    attemptLoop(label, uri, interpret, describe, attempt = 1)

  private def attemptLoop[A](
      label: String,
      uri: Uri,
      interpret: String => Either[RemoteFailure, A],
      describe: A => String,
      attempt: Int
  ): F[Either[RemoteFailure, A]] =
    val totalAttempts = config.maxRetries + 1
    timed(singleAttempt(uri, interpret)).flatMap {
      case (Right(value), elapsed) =>
        Logger[F]
          .info(
            s"$label: consulta completada en ${elapsed.toMillis} ms " +
              s"(intento $attempt/$totalAttempts, ${describe(value)})"
          )
          .as(Right(value))
      case (Left(failure), elapsed) if failure.transient && attempt < totalAttempts =>
        val delay = config.retryBackoff * (1L << (attempt - 1))
        Logger[F].warn(
          s"$label: intento $attempt/$totalAttempts falló tras ${elapsed.toMillis} ms " +
            s"(${failure.describe}); reintento en ${delay.toMillis} ms"
        ) *> Temporal[F].sleep(delay) *> attemptLoop(label, uri, interpret, describe, attempt + 1)
      case (Left(failure), elapsed) =>
        val message =
          s"$label: consulta fallida tras ${elapsed.toMillis} ms " +
            s"(intento $attempt/$totalAttempts, ${failure.describe})"
        val log = failure match
          case RemoteFailure.Unexpected(cause) => Logger[F].error(cause)(message)
          case _                               => Logger[F].error(message)
        log.as(Left(failure))
    }

  private def timed[A](action: F[A]): F[(A, FiniteDuration)] =
    for
      start  <- Temporal[F].monotonic
      result <- action
      end    <- Temporal[F].monotonic
    yield (result, end - start)

  private def singleAttempt[A](
      uri: Uri,
      interpret: String => Either[RemoteFailure, A]
  ): F[Either[RemoteFailure, A]] =
    val request = Request[F](Method.GET, uri).putHeaders(
      Accept(MediaType.application.json),
      RemoteHttpClient.userAgent
    )
    client
      .run(request)
      .use(response => response.bodyText.compile.string.map(body => (response.status, body)))
      .timeout(config.attemptTimeout)
      .attempt
      .map {
        case Right((status, body)) if status.isSuccess =>
          interpret(body)
        case Right((status, _)) if status.responseClass == Status.ServerError =>
          Left(RemoteFailure.ServerError(status))
        case Right((status, _)) =>
          Left(RemoteFailure.RejectedRequest(status))
        case Left(_: TimeoutException) =>
          Left(RemoteFailure.Timeout)
        case Left(error: (IOException | UnresolvedAddressException)) =>
          Left(RemoteFailure.ConnectionFailed(error))
        case Left(error) =>
          Left(RemoteFailure.Unexpected(error))
      }
