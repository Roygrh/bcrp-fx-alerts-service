package pe.quiroz.fxalerts.infrastructure.http.middleware

import cats.data.Kleisli
import cats.effect.{Async, Clock}
import cats.syntax.all.*
import org.http4s.{Header, HttpApp, Request}
import org.typelevel.ci.*
import org.typelevel.log4cats.Logger

import java.util.UUID
import scala.concurrent.duration.FiniteDuration

/**
 * Middleware de observabilidad básica.
 *
 * Por cada petición:
 *   - propaga el identificador de correlación recibido en `X-Request-Id` o genera uno nuevo, lo
 *     inyecta en la petición (para que los manejadores puedan incluirlo en sus trazas) y lo
 *     devuelve en la respuesta;
 *   - registra una única línea con método, ruta, código de estado, duración y, si la petición
 *     presenta un token válido, el `client_id` autenticado (`client=`), útil para auditoría.
 *
 * No se registran cuerpos, cabeceras ni parámetros de consulta: en particular, nunca la cabecera
 * `Authorization` ni el token. Solo la ruta, que en esta API no transporta datos de negocio más
 * allá de identificadores. Un identificador recibido se acepta únicamente si es corto y
 * alfanumérico, para que nunca llegue al log texto arbitrario del cliente.
 */
object RequestLogging:

  val requestIdHeader: CIString = ci"X-Request-Id"

  private val acceptedRequestId = "^[A-Za-z0-9._-]{1,64}$".r

  /** Sin resolución de identidad: las líneas de log no llevan `client=`. */
  def apply[F[_]: Async: Logger](app: HttpApp[F]): HttpApp[F] =
    apply(app, _ => none[String].pure[F])

  /**
   * @param subjectOf
   *   identidad autenticada de la petición, si presenta credenciales válidas; solo se usa para el
   *   log y nunca condiciona la respuesta
   */
  def apply[F[_]: Async: Logger](
      app: HttpApp[F],
      subjectOf: Request[F] => F[Option[String]]
  ): HttpApp[F] =
    Kleisli { request =>
      for
        requestId <- resolveRequestId(request)
        subject   <- subjectOf(request)
        start     <- Clock[F].monotonic
        result    <- app.run(request.putHeaders(Header.Raw(requestIdHeader, requestId))).attempt
        end       <- Clock[F].monotonic
        elapsed = end - start
        _ <- result match
          case Right(response) =>
            logLine(request, requestId, subject, response.status.code, elapsed)
          case Left(error) =>
            Logger[F].error(error)(line(request, requestId, subject, 500, elapsed))
        response <- Async[F].fromEither(result)
      yield response.putHeaders(Header.Raw(requestIdHeader, requestId))
    }

  private def resolveRequestId[F[_]: Async](request: Request[F]): F[String] =
    request.headers
      .get(requestIdHeader)
      .map(_.head.value.trim)
      .filter(acceptedRequestId.matches) match
      case Some(received) => received.pure[F]
      case None           => Async[F].delay(UUID.randomUUID().toString)

  private def logLine[F[_]: Logger](
      request: Request[F],
      requestId: String,
      subject: Option[String],
      status: Int,
      elapsed: FiniteDuration
  ): F[Unit] =
    val message = line(request, requestId, subject, status, elapsed)
    if status >= 500 then Logger[F].warn(message) else Logger[F].info(message)

  private def line[F[_]](
      request: Request[F],
      requestId: String,
      subject: Option[String],
      status: Int,
      elapsed: FiniteDuration
  ): String =
    s"${request.method.name} ${request.uri.path.renderString} -> $status " +
      s"(${elapsed.toMillis} ms) requestId=$requestId" +
      subject.fold("")(client => s" client=$client")
