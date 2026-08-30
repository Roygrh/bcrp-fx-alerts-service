package pe.quiroz.fxalerts.infrastructure.persistence

import cats.syntax.all.*
import doobie.Meta
import doobie.postgres.implicits.*
import pe.quiroz.fxalerts.domain.alert.{
  AlertId,
  AlertStatus,
  BcrpSeries,
  ClientId,
  CrossingDirection,
  Threshold
}

/**
 * Mapeo entre los tipos del dominio de alertas y los tipos SQL.
 *
 * Los tipos con constructor inteligente se decodifican a través de él (`tiemap`): una fila que
 * violase una invariante del dominio (solo posible si alguien escribió en la tabla sin pasar por la
 * aplicación) falla al leerse en lugar de producir una entidad inválida. Los códigos de texto de
 * los enumerados son un detalle de persistencia y por eso viven aquí, no en el dominio.
 */
object AlertMeta:

  given Meta[AlertId] = Meta[java.util.UUID].timap(AlertId(_))(_.value)

  given Meta[ClientId] =
    Meta[String].tiemap(raw => ClientId.from(raw).leftMap(_.message))(_.value)

  given Meta[Threshold] =
    Meta[BigDecimal].tiemap(raw => Threshold.from(raw).leftMap(_.message))(_.value)

  given Meta[BcrpSeries] =
    Meta[String].tiemap(code =>
      BcrpSeries.fromCode(code).toRight(s"Serie del BCRP desconocida: $code")
    )(_.code)

  given Meta[CrossingDirection] =
    Meta[String].tiemap {
      case "ABOVE" => Right(CrossingDirection.Above)
      case "BELOW" => Right(CrossingDirection.Below)
      case other   => Left(s"Dirección de cruce desconocida: $other")
    } {
      case CrossingDirection.Above => "ABOVE"
      case CrossingDirection.Below => "BELOW"
    }

  given Meta[AlertStatus] =
    Meta[String].tiemap {
      case "ACTIVE"   => Right(AlertStatus.Active)
      case "INACTIVE" => Right(AlertStatus.Inactive)
      case other      => Left(s"Estado de alerta desconocido: $other")
    } {
      case AlertStatus.Active   => "ACTIVE"
      case AlertStatus.Inactive => "INACTIVE"
    }
