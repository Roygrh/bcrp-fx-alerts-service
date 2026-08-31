package pe.quiroz.fxalerts.infrastructure.bcrp

import cats.syntax.all.*
import io.circe.parser.decode
import io.circe.{Decoder, HCursor}
import pe.quiroz.fxalerts.domain.alert.BcrpSeries
import pe.quiroz.fxalerts.domain.rate.{ExchangeRate, RateProvider}

import java.time.LocalDate
import scala.util.Try

/**
 * Observación de un periodo en la respuesta del BCRP.
 *
 * La API representa la ausencia de dato (fines de semana, feriados, dato aún no publicado) con la
 * cadena literal `"n.d."`, y los valores publicados como cadenas numéricas (`"3.523"`). Ambos casos
 * se modelan de forma explícita: `"n.d."` nunca se convierte en cero ni en excepción.
 */
enum BcrpValue:
  case Published(value: BigDecimal)
  case NotAvailable

object BcrpValue:

  private val notAvailableMarker = "n.d."

  given Decoder[BcrpValue] = Decoder.instance { cursor =>
    cursor
      .as[String]
      .flatMap { raw =>
        val text = raw.trim
        if text == notAvailableMarker then Right(NotAvailable)
        else
          Try(BigDecimal(text)).toEither
            .leftMap(_ => io.circe.DecodingFailure(s"Valor no numérico: '$text'", cursor.history))
            .map(Published.apply)
      }
      .orElse(cursor.as[BigDecimal].map(Published.apply))
  }

/** Metadatos de una serie en la respuesta (`config.series[i]`). */
final case class BcrpSeriesMeta(name: String, dec: Option[String])

object BcrpSeriesMeta:
  given Decoder[BcrpSeriesMeta] = Decoder.forProduct2("name", "dec")(BcrpSeriesMeta.apply)

/** Un periodo de la respuesta: la fecha (como texto de la API) y un valor por serie pedida. */
final case class BcrpPeriod(name: String, values: List[BcrpValue])

object BcrpPeriod:
  given Decoder[BcrpPeriod] = Decoder.forProduct2("name", "values")(BcrpPeriod.apply)

/**
 * Respuesta de `GET /estadisticas/series/api/{código}/json/{inicio}/{fin}/{idioma}`.
 *
 * Forma de la respuesta para una serie diaria:
 * {{{
 * {
 *   "config": {
 *     "title": "Tipo de cambio - TC Sistema bancario SBS (S/ por US$) - Venta",
 *     "series": [ { "name": "TC Sistema bancario SBS (S/ por US$) - Venta", "dec": "3" } ]
 *   },
 *   "periods": [
 *     { "name": "28.Ago.26", "values": [ "3.523" ] },
 *     { "name": "29.Ago.26", "values": [ "n.d." ] }
 *   ]
 * }
 * }}}
 *
 * Solo `periods[].name` y `periods[].values` son imprescindibles; `config` se decodifica de forma
 * tolerante porque no aporta nada al negocio. `values` es posicional: la posición `i` corresponde a
 * la serie `i` pedida en la URL (este servicio pide una sola).
 */
final case class BcrpResponse(config: Option[BcrpResponseConfig], periods: List[BcrpPeriod])

final case class BcrpResponseConfig(title: Option[String], series: List[BcrpSeriesMeta])

object BcrpResponseConfig:
  given Decoder[BcrpResponseConfig] = Decoder.instance { (cursor: HCursor) =>
    (
      cursor.get[Option[String]]("title"),
      cursor.getOrElse[List[BcrpSeriesMeta]]("series")(Nil)
    ).mapN(BcrpResponseConfig.apply)
  }

object BcrpResponse:

  given Decoder[BcrpResponse] = Decoder.instance { (cursor: HCursor) =>
    (
      cursor.get[Option[BcrpResponseConfig]]("config"),
      cursor.get[List[BcrpPeriod]]("periods")
    ).mapN(BcrpResponse.apply)
  }

  /**
   * Decodifica el cuerpo de la respuesta. Un cuerpo que no es JSON (por ejemplo, la página HTML
   * interpuesta por el proxy de seguridad del BCRP cuando no reconoce al cliente) produce `Left`
   * con un motivo apto para el log.
   */
  def parse(body: String): Either[String, BcrpResponse] =
    decode[BcrpResponse](body).leftMap(_.getMessage)

  /**
   * Último valor publicado dentro de la respuesta, o `None` si ningún periodo trae dato.
   *
   * La API devuelve los periodos en orden cronológico, pero no se depende de ello: se elige la
   * fecha mayor entre los periodos con valor. Un nombre de periodo que no se puede interpretar como
   * fecha, o un periodo sin valores, invalida la respuesta completa (`Left`): significa que el
   * contrato de la API cambió y es preferible fallar de forma visible a devolver un dato con fecha
   * equivocada.
   */
  def latestPublished(
      response: BcrpResponse,
      series: BcrpSeries
  ): Either[String, Option[ExchangeRate]] =
    response.periods
      .traverse { period =>
        for
          date  <- BcrpPeriodDate.parse(period.name)
          value <- period.values.headOption.toRight(s"El periodo '${period.name}' no trae valores")
        yield (date, value)
      }
      .map { observations =>
        observations
          .collect { case (date, BcrpValue.Published(value)) =>
            ExchangeRate(series, date, value, RateProvider.Bcrp)
          }
          .maxByOption(_.date)
      }

/**
 * Fechas de los periodos diarios tal como las escribe la API: `dd.Mmm.yy` con el mes abreviado en
 * español (`02.Ene.97`, `28.Ago.26`). Se admite también el año con cuatro cifras y ambas
 * abreviaturas de setiembre (`Set` y `Sep`), que conviven en las publicaciones del BCRP.
 *
 * Los años de dos cifras se interpretan en el siglo XXI: este servicio consulta ventanas recientes
 * y los tipos de cambio del siglo pasado no le interesan.
 */
object BcrpPeriodDate:

  private val pattern = """^(\d{1,2})\.([A-Za-z]{3})\.(\d{2}|\d{4})$""".r

  private val months: Map[String, Int] = Map(
    "ene" -> 1,
    "feb" -> 2,
    "mar" -> 3,
    "abr" -> 4,
    "may" -> 5,
    "jun" -> 6,
    "jul" -> 7,
    "ago" -> 8,
    "set" -> 9,
    "sep" -> 9,
    "oct" -> 10,
    "nov" -> 11,
    "dic" -> 12
  )

  def parse(raw: String): Either[String, LocalDate] =
    raw.trim match
      case pattern(day, month, year) =>
        for
          monthNumber <- months.get(month.toLowerCase).toRight(s"Mes desconocido en '$raw'")
          fullYear = if year.length == 2 then 2000 + year.toInt else year.toInt
          date <- Try(LocalDate.of(fullYear, monthNumber, day.toInt)).toEither.leftMap(_ =>
            s"Fecha inválida en '$raw'"
          )
        yield date
      case _ =>
        Left(s"Periodo con formato no reconocido: '$raw'")
