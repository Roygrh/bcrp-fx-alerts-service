package pe.quiroz.fxalerts.application.health

import cats.effect.Temporal
import cats.effect.syntax.all.*
import cats.syntax.all.*
import org.typelevel.log4cats.Logger

import scala.concurrent.duration.FiniteDuration

/**
 * Servicio de aplicación que agrega el estado de los componentes del sistema.
 *
 * Un fallo o un tiempo de espera agotado en la verificación de base de datos se traduce en `Down`;
 * el detalle técnico se registra en el log y nunca se expone al cliente HTTP.
 */
final class HealthService[F[_]: Temporal: Logger](
    database: DatabaseHealthCheck[F],
    databaseTimeout: FiniteDuration
):

  def check: F[HealthReport] =
    database.ping
      .timeout(databaseTimeout)
      .attempt
      .flatMap {
        case Right(_) =>
          HealthReport.fromComponents(database = ComponentStatus.Up).pure[F]
        case Left(error) =>
          Logger[F]
            .warn(error)("La verificación de salud de la base de datos falló")
            .as(HealthReport.fromComponents(database = ComponentStatus.Down))
      }
