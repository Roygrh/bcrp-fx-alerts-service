package pe.quiroz.fxalerts.infrastructure.http.rate

import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.{Codec, Decoder, Encoder}
import pe.quiroz.fxalerts.application.rate.{Freshness, RateSnapshot}
import pe.quiroz.fxalerts.domain.alert.BcrpSeries
import pe.quiroz.fxalerts.domain.rate.RateProvider
import pe.quiroz.fxalerts.infrastructure.http.alert.AlertJson.given
import sttp.tapir.Schema

import java.time.{Duration, Instant, LocalDate}

/**
 * Procedencia del dato en el contrato HTTP: quién lo produjo, si es oficial y qué mide.
 *
 * Un consumidor debe poder distinguir un precio oficial de una referencia de mercado sin conocer la
 * implementación; por eso `official` es un booleano explícito y `measures` es texto legible.
 *
 * @param id
 *   código estable de la fuente (`BCRP`, `ERAPI`)
 * @param attribution
 *   reconocimiento exigido por el proveedor, cuando aplica
 */
final case class RateSourceResponse(
    id: String,
    name: String,
    official: Boolean,
    measures: String,
    attribution: Option[String]
) derives Schema

object RateSourceResponse:

  given Codec.AsObject[RateSourceResponse] = Codec.AsObject.from(
    deriveDecoder[RateSourceResponse],
    deriveEncoder[RateSourceResponse].mapJsonObject(_.filter { case (_, value) => !value.isNull })
  )

  /** Atribución exigida por las condiciones de uso de cada proveedor, si las tiene. */
  def attributionFor(provider: RateProvider): Option[String] =
    provider match
      case RateProvider.Bcrp            => None
      case RateProvider.ExchangeRateApi =>
        Some("Rates By Exchange Rate API (https://www.exchangerate-api.com)")

  def from(provider: RateProvider): RateSourceResponse =
    RateSourceResponse(
      id = provider.code,
      name = provider.name,
      official = provider.official,
      measures = provider.measures,
      attribution = attributionFor(provider)
    )

/**
 * Representación pública del tipo de cambio vigente.
 *
 * @param series
 *   código de la serie de referencia solicitada; `source.measures` precisa qué representa el valor
 * @param value
 *   soles por dólar
 * @param date
 *   fecha del dato según el calendario peruano
 * @param retrievedAt
 *   instante en que la fuente entregó el dato
 * @param ageSeconds
 *   segundos transcurridos desde `retrievedAt` en el momento de responder
 * @param freshness
 *   `FRESH` si la fuente confirmó el dato dentro del periodo de validez; `STALE` si ninguna fuente
 *   responde y se entrega el último valor conocido
 * @param source
 *   fuente que entregó el dato, si es oficial y qué mide
 */
final case class RateResponse(
    series: BcrpSeries,
    value: BigDecimal,
    date: LocalDate,
    retrievedAt: Instant,
    ageSeconds: Long,
    freshness: Freshness,
    source: RateSourceResponse
) derives Codec.AsObject,
      Schema

object RateResponse:

  private def wireFreshness(value: Freshness): String = value.toString.toUpperCase

  given Encoder[Freshness] = Encoder.encodeString.contramap(wireFreshness)

  given Decoder[Freshness] = Decoder.decodeString.emap { raw =>
    Freshness.values.find(wireFreshness(_) == raw).toRight(s"Frescura desconocida: $raw")
  }

  given Schema[Freshness] = Schema
    .derivedEnumeration[Freshness](encode = Some(wireFreshness))
    .description(
      "FRESH: confirmado por la fuente dentro del periodo de validez; " +
        "STALE: último valor conocido, ninguna fuente responde"
    )

  def from(snapshot: RateSnapshot, now: Instant): RateResponse =
    RateResponse(
      series = snapshot.rate.series,
      value = snapshot.rate.value,
      date = snapshot.rate.date,
      retrievedAt = snapshot.retrievedAt,
      ageSeconds = Duration.between(snapshot.retrievedAt, now).getSeconds.max(0L),
      freshness = snapshot.freshness,
      source = RateSourceResponse.from(snapshot.rate.provider)
    )

  val example: RateResponse = RateResponse(
    series = BcrpSeries.UsdPenSbsSell,
    value = BigDecimal("3.523"),
    date = LocalDate.parse("2026-08-28"),
    retrievedAt = Instant.parse("2026-08-30T15:30:00Z"),
    ageSeconds = 42L,
    freshness = Freshness.Fresh,
    source = RateSourceResponse.from(RateProvider.Bcrp)
  )

  /** Ejemplo servido desde el respaldo: mismo contrato, `source.official = false` y atribución. */
  val exampleFallback: RateResponse = RateResponse(
    series = BcrpSeries.UsdPenSbsSell,
    value = BigDecimal("3.350827"),
    date = LocalDate.parse("2026-08-30"),
    retrievedAt = Instant.parse("2026-08-31T02:38:47Z"),
    ageSeconds = 42L,
    freshness = Freshness.Fresh,
    source = RateSourceResponse.from(RateProvider.ExchangeRateApi)
  )
