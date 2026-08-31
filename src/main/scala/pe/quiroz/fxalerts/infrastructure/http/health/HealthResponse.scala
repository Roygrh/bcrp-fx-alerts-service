package pe.quiroz.fxalerts.infrastructure.http.health

import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.{Codec, Decoder, Encoder}
import pe.quiroz.fxalerts.application.health.{
  ComponentHealth,
  ComponentStatus,
  HealthReport,
  RateHealth
}
import sttp.tapir.Schema

/**
 * Representación JSON del estado del servicio expuesta en `GET /health`.
 *
 * @param status
 *   estado agregado: `UP`, `DEGRADED` o `DOWN`
 * @param database
 *   conexión a PostgreSQL
 * @param rates
 *   obtención del tipo de cambio: estado, fuente que sirve actualmente y si es oficial
 */
final case class HealthResponse(
    status: ComponentStatus,
    database: HealthResponse.Component,
    rates: HealthResponse.RateComponent
) derives Codec.AsObject,
      Schema

object HealthResponse:

  private def wireValue(status: ComponentStatus): String = status.toString.toUpperCase

  given Encoder[ComponentStatus] = Encoder.encodeString.contramap(wireValue)

  given Decoder[ComponentStatus] = Decoder.decodeString.emap { raw =>
    ComponentStatus.values
      .find(wireValue(_) == raw)
      .toRight(s"Estado desconocido: $raw")
  }

  given Schema[ComponentStatus] =
    Schema.derivedEnumeration[ComponentStatus](encode = Some(wireValue))

  /**
   * Estado de un componente. `detail` es una explicación breve para el operador y se omite del JSON
   * cuando no aporta nada.
   */
  final case class Component(status: ComponentStatus, detail: Option[String]) derives Schema

  given Codec.AsObject[Component] = Codec.AsObject.from(
    deriveDecoder[Component],
    deriveEncoder[Component].mapJsonObject(_.filter { case (_, value) => !value.isNull })
  )

  /**
   * Estado de la obtención del tipo de cambio.
   *
   * @param source
   *   código de la fuente que entregó el dato que se está sirviendo (`BCRP`, `ERAPI`...)
   * @param official
   *   si esa fuente es un precio oficial de referencia
   */
  final case class RateComponent(
      status: ComponentStatus,
      detail: Option[String],
      source: Option[String],
      official: Option[Boolean]
  ) derives Schema

  given Codec.AsObject[RateComponent] = Codec.AsObject.from(
    deriveDecoder[RateComponent],
    deriveEncoder[RateComponent].mapJsonObject(_.filter { case (_, value) => !value.isNull })
  )

  private def component(health: ComponentHealth): Component =
    Component(health.status, health.detail)

  private def rateComponent(health: RateHealth): RateComponent =
    RateComponent(
      status = health.status,
      detail = health.detail,
      source = health.provider.map(_.code),
      official = health.provider.map(_.official)
    )

  def from(report: HealthReport): HealthResponse =
    HealthResponse(
      status = report.status,
      database = component(report.database),
      rates = rateComponent(report.rates)
    )

  val exampleUp: HealthResponse =
    HealthResponse(
      ComponentStatus.Up,
      Component(ComponentStatus.Up, None),
      RateComponent(ComponentStatus.Up, None, Some("BCRP"), Some(true))
    )

  val exampleDegraded: HealthResponse =
    HealthResponse(
      ComponentStatus.Degraded,
      Component(ComponentStatus.Up, None),
      RateComponent(
        ComponentStatus.Degraded,
        Some(
          "Se sirve desde la fuente de respaldo ERAPI (no oficial); la fuente oficial no responde"
        ),
        Some("ERAPI"),
        Some(false)
      )
    )

  val exampleDown: HealthResponse =
    HealthResponse(
      ComponentStatus.Down,
      Component(ComponentStatus.Down, Some("La base de datos no responde")),
      RateComponent(ComponentStatus.Up, None, Some("BCRP"), Some(true))
    )
