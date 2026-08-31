package pe.quiroz.fxalerts.domain.rate

import java.time.ZoneId

/**
 * Calendario de referencia del servicio.
 *
 * Las fechas de los tipos de cambio ([[ExchangeRate.date]]) se expresan en el calendario peruano,
 * que es el de la fuente oficial: un dato publicado a las 00:02 UTC pertenece todavía al día
 * anterior en Lima.
 */
object PeruvianCalendar:

  val zone: ZoneId = ZoneId.of("America/Lima")
