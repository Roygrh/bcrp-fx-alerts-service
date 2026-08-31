package pe.quiroz.fxalerts.infrastructure.bcrp

import munit.FunSuite
import pe.quiroz.fxalerts.domain.alert.BcrpSeries
import pe.quiroz.fxalerts.domain.rate.{ExchangeRate, RateProvider}

import java.time.LocalDate
import scala.io.Source

/** Parseo de la respuesta del BCRP sin red: el ejemplo capturado vive en `src/test/resources`. */
class BcrpResponseSuite extends FunSuite:

  private val series = BcrpSeries.UsdPenSbsSell

  private def fixture(name: String): String =
    val stream = Option(getClass.getResourceAsStream(s"/bcrp/$name"))
      .getOrElse(fail(s"No se encuentra el recurso de prueba bcrp/$name"))
    try Source.fromInputStream(stream, "UTF-8").mkString
    finally stream.close()

  private val sample = fixture("PD04640PD-2026-08-20_2026-08-30.json")

  test("decodifica la respuesta real: metadatos, periodos y valores"):
    val response = BcrpResponse.parse(sample).fold(fail(_), identity)
    assertEquals(response.periods.size, 11)
    assertEquals(response.config.flatMap(_.series.headOption).map(_.dec), Some(Some("3")))
    assertEquals(
      response.periods.head,
      BcrpPeriod("20.Ago.26", List(BcrpValue.Published(BigDecimal("3.528"))))
    )

  test("los días sin dato (\"n.d.\") se modelan como NotAvailable, nunca como cero"):
    val response = BcrpResponse.parse(sample).fold(fail(_), identity)
    val weekend  = response.periods.filter(p => Set("22.Ago.26", "23.Ago.26").contains(p.name))
    assertEquals(
      weekend.map(_.values),
      List(List(BcrpValue.NotAvailable), List(BcrpValue.NotAvailable))
    )

  test("el último dato publicado ignora los periodos \"n.d.\" posteriores"):
    val latest = BcrpResponse.parse(sample).flatMap(BcrpResponse.latestPublished(_, series))
    assertEquals(
      latest,
      Right(
        Some(
          ExchangeRate(series, LocalDate.of(2026, 8, 28), BigDecimal("3.523"), RateProvider.Bcrp)
        )
      )
    )

  test("el último dato se elige por fecha aunque los periodos lleguen desordenados"):
    val body =
      """{"periods":[{"name":"28.Ago.26","values":["3.523"]},{"name":"26.Ago.26","values":["3.522"]}]}"""
    val latest = BcrpResponse.parse(body).flatMap(BcrpResponse.latestPublished(_, series))
    assertEquals(latest.map(_.map(_.date)), Right(Some(LocalDate.of(2026, 8, 28))))

  test("una ventana sin ningún dato publicado produce None, no un error"):
    val body =
      """{"config":{"series":[{"name":"x","dec":"3"}]},"periods":[{"name":"29.Ago.26","values":["n.d."]},{"name":"30.Ago.26","values":["n.d."]}]}"""
    val latest = BcrpResponse.parse(body).flatMap(BcrpResponse.latestPublished(_, series))
    assertEquals(latest, Right(None))

  test("una respuesta sin periodos produce None"):
    val latest =
      BcrpResponse.parse("""{"periods":[]}""").flatMap(BcrpResponse.latestPublished(_, series))
    assertEquals(latest, Right(None))

  test("acepta valores numéricos sin comillas por tolerancia"):
    val body   = """{"periods":[{"name":"28.Ago.26","values":[3.523]}]}"""
    val latest = BcrpResponse.parse(body).flatMap(BcrpResponse.latestPublished(_, series))
    assertEquals(latest.map(_.map(_.value)), Right(Some(BigDecimal("3.523"))))

  test("un valor que no es numérico ni \"n.d.\" invalida la respuesta"):
    val body = """{"periods":[{"name":"28.Ago.26","values":["s/d"]}]}"""
    assert(BcrpResponse.parse(body).isLeft)

  test("un periodo con fecha irreconocible invalida la respuesta completa"):
    val body   = """{"periods":[{"name":"2026-08-28","values":["3.523"]}]}"""
    val latest = BcrpResponse.parse(body).flatMap(BcrpResponse.latestPublished(_, series))
    assert(latest.isLeft, latest.toString)

  test("un periodo sin valores invalida la respuesta completa"):
    val body   = """{"periods":[{"name":"28.Ago.26","values":[]}]}"""
    val latest = BcrpResponse.parse(body).flatMap(BcrpResponse.latestPublished(_, series))
    assert(latest.isLeft, latest.toString)

  test("un cuerpo HTML (página de desafío del proxy) no es JSON y se rechaza con motivo"):
    val html =
      "<!DOCTYPE html><html><head><script src=\"/x\" async></script></head><body></body></html>"
    assert(BcrpResponse.parse(html).isLeft)

  test("las fechas de periodo admiten dd.Mmm.aa, dd.Mmm.aaaa y ambas abreviaturas de setiembre"):
    assertEquals(BcrpPeriodDate.parse("28.Ago.26"), Right(LocalDate.of(2026, 8, 28)))
    assertEquals(BcrpPeriodDate.parse("1.Ene.26"), Right(LocalDate.of(2026, 1, 1)))
    assertEquals(BcrpPeriodDate.parse("15.Set.25"), Right(LocalDate.of(2025, 9, 15)))
    assertEquals(BcrpPeriodDate.parse("15.Sep.25"), Right(LocalDate.of(2025, 9, 15)))
    assertEquals(BcrpPeriodDate.parse("01.Dic.2025"), Right(LocalDate.of(2025, 12, 1)))
    assertEquals(BcrpPeriodDate.parse(" 31.dic.25 "), Right(LocalDate.of(2025, 12, 31)))

  test("las fechas de periodo inválidas se rechazan con motivo"):
    assert(BcrpPeriodDate.parse("31.Feb.26").isLeft)
    assert(BcrpPeriodDate.parse("28.Xyz.26").isLeft)
    assert(BcrpPeriodDate.parse("Ago.2026").isLeft)
    assert(BcrpPeriodDate.parse("").isLeft)
