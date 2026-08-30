package pe.quiroz.fxalerts.infrastructure.http.alert

import io.circe.{Decoder, Encoder}
import pe.quiroz.fxalerts.domain.alert.{AlertStatus, BcrpSeries, CrossingDirection}
import sttp.tapir.Schema

/**
 * Representación en el contrato HTTP de los catálogos cerrados del dominio.
 *
 * Igual que hace la persistencia en `AlertMeta`, la forma textual de cada enumerado se fija aquí,
 * en la frontera, y no en el dominio: el contrato público puede renombrar un valor sin tocar el
 * modelo, y viceversa. Un valor no admitido produce un error de decodificación que enumera los
 * valores válidos, sin repetir el texto recibido.
 */
object AlertJson:

  /**
   * Las series viajan por su código oficial en la API del BCRP, que es su identificador estable.
   */
  given Encoder[BcrpSeries] = Encoder.encodeString.contramap(_.code)

  given Decoder[BcrpSeries] =
    enumDecoder("la serie del BCRP", BcrpSeries.values.toList, _.code)

  given Schema[BcrpSeries] = Schema
    .derivedEnumeration[BcrpSeries](encode = Some(_.code))
    .description("Código de la serie estadística del BCRP observada por la alerta")

  private def wireDirection(value: CrossingDirection): String = value.toString.toUpperCase

  given Encoder[CrossingDirection] = Encoder.encodeString.contramap(wireDirection)

  given Decoder[CrossingDirection] =
    enumDecoder("la dirección de cruce", CrossingDirection.values.toList, wireDirection)

  given Schema[CrossingDirection] = Schema
    .derivedEnumeration[CrossingDirection](encode = Some(wireDirection))
    .description("Sentido del cruce del umbral que dispara la alerta")

  private def wireStatus(value: AlertStatus): String = value.toString.toUpperCase

  given Encoder[AlertStatus] = Encoder.encodeString.contramap(wireStatus)

  given Decoder[AlertStatus] =
    enumDecoder("el estado de la alerta", AlertStatus.values.toList, wireStatus)

  given Schema[AlertStatus] = Schema
    .derivedEnumeration[AlertStatus](encode = Some(wireStatus))
    .description("Estado de la alerta: solo las activas se evalúan")

  private def enumDecoder[E](label: String, values: List[E], wire: E => String): Decoder[E] =
    val admitted = values.map(wire).mkString(", ")
    Decoder.decodeString.emap(raw =>
      values.find(wire(_) == raw).toRight(s"Valor no admitido para $label; se admite: $admitted")
    )
