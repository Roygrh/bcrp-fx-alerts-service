package pe.quiroz.fxalerts.infrastructure.http.health

import io.circe.{Codec, Decoder, Encoder}
import pe.quiroz.fxalerts.application.health.{ComponentStatus, HealthReport}
import sttp.tapir.Schema

/** Representación JSON del estado del servicio expuesta en `GET /health`. */
final case class HealthResponse(status: ComponentStatus, database: HealthResponse.Component)
    derives Codec.AsObject,
      Schema

object HealthResponse:

  final case class Component(status: ComponentStatus) derives Codec.AsObject, Schema

  def from(report: HealthReport): HealthResponse =
    HealthResponse(status = report.status, database = Component(report.database))

  val exampleUp: HealthResponse =
    HealthResponse(ComponentStatus.Up, Component(ComponentStatus.Up))

  val exampleDown: HealthResponse =
    HealthResponse(ComponentStatus.Down, Component(ComponentStatus.Down))

  private def wireValue(status: ComponentStatus): String = status.toString.toUpperCase

  given Encoder[ComponentStatus] = Encoder.encodeString.contramap(wireValue)

  given Decoder[ComponentStatus] = Decoder.decodeString.emap { raw =>
    ComponentStatus.values
      .find(wireValue(_) == raw)
      .toRight(s"Estado desconocido: $raw")
  }

  given Schema[ComponentStatus] =
    Schema.derivedEnumeration[ComponentStatus](encode = Some(wireValue))
