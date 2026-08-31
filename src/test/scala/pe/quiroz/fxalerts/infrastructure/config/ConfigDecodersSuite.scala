package pe.quiroz.fxalerts.infrastructure.config

import cats.data.NonEmptyList
import munit.FunSuite
import pe.quiroz.fxalerts.application.security.Scope
import pe.quiroz.fxalerts.domain.rate.RateProvider
import pe.quiroz.fxalerts.infrastructure.security.Pbkdf2SecretHasher

import scala.concurrent.duration.*

/**
 * Lógica de los decodificadores propios (`RATE_SOURCES`, `OAUTH_CLIENTS`, rangos), aislada del
 * cableado (ver [[ConfigLoaderSuite]]).
 */
class ConfigDecodersSuite extends FunSuite:

  private def decode(raw: String) = ConfigDecoders.providerList.decode(None, raw)

  test("acepta la lista por defecto en orden"):
    assertEquals(
      decode("BCRP,ERAPI"),
      Right(NonEmptyList.of(RateProvider.Bcrp, RateProvider.ExchangeRateApi))
    )

  test("acepta el orden invertido, espacios y minúsculas"):
    assertEquals(
      decode(" erapi , bcrp "),
      Right(NonEmptyList.of(RateProvider.ExchangeRateApi, RateProvider.Bcrp))
    )

  test("una sola fuente desactiva el respaldo"):
    assertEquals(decode("BCRP"), Right(NonEmptyList.one(RateProvider.Bcrp)))

  test("rechaza la lista vacía"):
    assert(decode("").isLeft)
    assert(decode(" , ").isLeft)

  test("rechaza códigos desconocidos"):
    assert(decode("BCRP,SUNAT").isLeft)

  test("rechaza códigos repetidos"):
    assert(decode("BCRP,ERAPI,BCRP").isLeft)

  test("atLeast rechaza valores por debajo del mínimo y acepta el resto"):
    assert(ConfigDecoders.atLeast(0).decode(None, "-1").isLeft)
    assertEquals(ConfigDecoders.atLeast(0).decode(None, "0"), Right(0))
    assertEquals(ConfigDecoders.atLeast(1).decode(None, "7"), Right(7))

  test("durationBetween acepta el rango cerrado y rechaza fuera de él"):
    val decoder = ConfigDecoders.durationBetween(1.minute, 24.hours)
    assertEquals(decoder.decode(None, "1m"), Right(1.minute))
    assertEquals(decoder.decode(None, "24h"), Right(24.hours))
    assert(decoder.decode(None, "59s").isLeft)
    assert(decoder.decode(None, "25h").isLeft)

  // --- Registro de clientes --------------------------------------------------------------------

  private val hash = Pbkdf2SecretHasher.hashWith("s", Array.fill[Byte](16)(1), 1_000).encoded

  private def clients(raw: String) = ConfigDecoders.clientList.decode(None, raw)

  test("acepta varias entradas con espacios alrededor y alcances separados por comas"):
    val result =
      clients(s" cliente-001 | $hash | alerts:read, alerts:write ; monitor | $hash | rates:read ")
    val parsed = result.getOrElse(fail(s"Se esperaba un registro válido: $result"))
    assertEquals(parsed.size, 2)
    assertEquals(parsed.head.id.value, "cliente-001")
    assertEquals(parsed.head.scopes, Set(Scope.AlertsRead, Scope.AlertsWrite))
    assertEquals(parsed.toList(1).id.value, "monitor")
    assertEquals(parsed.toList(1).scopes, Set(Scope.RatesRead))

  test("rechaza el registro vacío, una entrada sin tres campos y un cliente sin alcances"):
    assert(clients("").isLeft)
    assert(clients(s"cliente-001|$hash").isLeft)
    assert(clients(s"cliente-001|$hash|").isLeft)

  test("rechaza identificadores con caracteres fuera del formato admitido"):
    assert(clients(s"cliente:001|$hash|alerts:read").isLeft)
    assert(clients(s"cliente 001|$hash|alerts:read").isLeft)
    assert(clients(s"${"c" * 65}|$hash|alerts:read").isLeft)

  test("rechaza un hash que el adaptador no interpreta, sin reproducirlo en el error"):
    val result = clients("cliente-001|bcrypt-2b-12-VALOR|alerts:read")
    assert(result.isLeft)
    assert(result.left.forall(!_.messages.exists(_.contains("VALOR"))))
