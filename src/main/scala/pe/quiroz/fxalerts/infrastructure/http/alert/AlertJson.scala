package pe.quiroz.fxalerts.infrastructure.http.alert

import io.circe.{Decoder, Encoder}
import pe.quiroz.fxalerts.domain.alert.{
  AlertOutcome,
  AlertStatus,
  BcrpSeries,
  CrossingDirection,
  EvaluationBasis
}
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

  /**
   * El resultado de la evaluación se nombra explícitamente en el contrato (no a partir del nombre
   * del caso) porque los consumidores discriminan por él y no debe cambiar si el dominio renombra
   * un caso.
   */
  private def wireOutcome(value: AlertOutcome): String =
    value match
      case AlertOutcome.Triggered      => "TRIGGERED"
      case AlertOutcome.NotTriggered   => "NOT_TRIGGERED"
      case AlertOutcome.Inactive       => "INACTIVE"
      case AlertOutcome.SeriesMismatch => "SERIES_MISMATCH"

  given Encoder[AlertOutcome] = Encoder.encodeString.contramap(wireOutcome)

  given Decoder[AlertOutcome] =
    enumDecoder("el resultado de la evaluación", AlertOutcome.values.toList, wireOutcome)

  given Schema[AlertOutcome] = Schema
    .derivedEnumeration[AlertOutcome](encode = Some(wireOutcome))
    .description(
      "TRIGGERED: el valor cruzó el umbral en el sentido configurado; NOT_TRIGGERED: no lo " +
        "cruzó; INACTIVE: la alerta está inactiva y no se evaluó; SERIES_MISMATCH: la alerta " +
        "observa otra serie y no se evaluó"
    )

  private def wireBasis(value: EvaluationBasis): String =
    value match
      case EvaluationBasis.OfficialConfirmed => "OFFICIAL_CONFIRMED"
      case EvaluationBasis.MarketReference   => "MARKET_REFERENCE"
      case EvaluationBasis.Unconfirmed       => "UNCONFIRMED"

  given Encoder[EvaluationBasis] = Encoder.encodeString.contramap(wireBasis)

  given Decoder[EvaluationBasis] =
    enumDecoder("la base de la evaluación", EvaluationBasis.values.toList, wireBasis)

  given Schema[EvaluationBasis] = Schema
    .derivedEnumeration[EvaluationBasis](encode = Some(wireBasis))
    .description(
      "Calidad del dato sobre el que se evaluaron las alertas. OFFICIAL_CONFIRMED: precio " +
        "oficial confirmado por su fuente dentro del periodo de validez (la única base " +
        "concluyente); MARKET_REFERENCE: referencia de mercado no oficial, confirmada; " +
        "UNCONFIRMED: último valor conocido, ninguna fuente pudo confirmarlo"
    )

  private def enumDecoder[E](label: String, values: List[E], wire: E => String): Decoder[E] =
    val admitted = values.map(wire).mkString(", ")
    Decoder.decodeString.emap(raw =>
      values.find(wire(_) == raw).toRight(s"Valor no admitido para $label; se admite: $admitted")
    )
