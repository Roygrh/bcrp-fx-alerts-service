package pe.quiroz.fxalerts.application.health

import cats.effect.Temporal
import cats.effect.syntax.all.*
import cats.syntax.all.*
import org.typelevel.log4cats.Logger
import pe.quiroz.fxalerts.application.rate.{ExchangeRateService, Freshness, RateSnapshot}
import pe.quiroz.fxalerts.domain.DomainError.{ExchangeRateNotPublished, ExchangeRateUnavailable}

import scala.concurrent.duration.FiniteDuration

/**
 * Servicio de aplicación que agrega el estado de los componentes del sistema.
 *
 * Base de datos: un fallo o un tiempo de espera agotado se traduce en `Down`.
 *
 * Tipo de cambio: se consulta a través del mismo puerto que usa la API (cadena de fuentes con su
 * caché delante), de modo que la verificación responde a la pregunta operativa "¿puede el servicio
 * entregar un tipo de cambio ahora mismo, y de qué calidad?" sin generar tráfico adicional hacia
 * las fuentes mientras la caché esté vigente:
 *
 *   - dato `Fresh` de una fuente oficial → `Up`;
 *   - dato `Fresh` de una fuente no oficial (respaldo) → `Degraded`: el servicio opera, pero sobre
 *     una referencia de mercado, no sobre el precio oficial;
 *   - dato `Stale` (las fuentes fallan pero hay valor en caché) → `Degraded`;
 *   - fuente accesible pero sin dato publicado en la ventana → `Up` (la fuente funciona);
 *   - ninguna fuente accesible y nada en caché → `Down`;
 *   - la verificación no concluye dentro del tiempo límite → `Degraded`, porque no se puede afirmar
 *     ni lo uno ni lo otro y la consulta en curso seguirá su curso en segundo plano.
 *
 * El detalle técnico de cada fallo se registra en el log y nunca se expone al cliente HTTP.
 */
final class HealthService[F[_]: Temporal: Logger](
    database: DatabaseHealthCheck[F],
    databaseTimeout: FiniteDuration,
    rates: ExchangeRateService[F],
    rateSourceTimeout: FiniteDuration
):

  def check: F[HealthReport] =
    databaseHealth.both(rateHealth).map { case (databaseStatus, rateStatus) =>
      HealthReport.fromComponents(database = databaseStatus, rates = rateStatus)
    }

  private def databaseHealth: F[ComponentHealth] =
    database.ping
      .timeout(databaseTimeout)
      .attempt
      .flatMap {
        case Right(_) =>
          ComponentHealth(ComponentStatus.Up).pure[F]
        case Left(error) =>
          Logger[F]
            .warn(error)("La verificación de salud de la base de datos falló")
            .as(ComponentHealth(ComponentStatus.Down, Some("La base de datos no responde")))
      }

  private def rateHealth: F[RateHealth] =
    rates.current
      .timeout(rateSourceTimeout)
      .attempt
      .flatMap {
        case Right(Right(snapshot)) =>
          servedHealth(snapshot).pure[F]
        case Right(Left(ExchangeRateNotPublished(_))) =>
          RateHealth(
            ComponentStatus.Up,
            Some("La fuente responde pero no hay dato publicado en la ventana consultada"),
            None
          ).pure[F]
        case Right(Left(ExchangeRateUnavailable(_))) =>
          RateHealth(
            ComponentStatus.Down,
            Some("Ninguna fuente de tipo de cambio responde y no hay dato en caché"),
            None
          ).pure[F]
        case Left(error) =>
          Logger[F]
            .warn(error)("La verificación de salud del tipo de cambio no concluyó")
            .as(
              RateHealth(
                ComponentStatus.Degraded,
                Some("La verificación del tipo de cambio no concluyó dentro del tiempo límite"),
                None
              )
            )
      }

  private def servedHealth(snapshot: RateSnapshot): RateHealth =
    val provider = snapshot.rate.provider
    (snapshot.freshness, provider.official) match
      case (Freshness.Fresh, true) =>
        RateHealth(ComponentStatus.Up, None, Some(provider))
      case (Freshness.Fresh, false) =>
        RateHealth(
          ComponentStatus.Degraded,
          Some(
            s"Se sirve desde la fuente de respaldo ${provider.code} (no oficial); " +
              "la fuente oficial no responde"
          ),
          Some(provider)
        )
      case (Freshness.Stale, _) =>
        RateHealth(
          ComponentStatus.Degraded,
          Some(
            "Las fuentes no responden; se sirve el último dato conocido " +
              s"(${snapshot.rate.date}, ${provider.code})"
          ),
          Some(provider)
        )
