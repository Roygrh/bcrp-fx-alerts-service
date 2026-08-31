package pe.quiroz.fxalerts.infrastructure.config

import cats.data.NonEmptyList
import munit.FunSuite
import pe.quiroz.fxalerts.domain.rate.RateProvider

/** Decodificación de `RATE_SOURCES`: orden de fuentes configurable sin recompilar. */
class ConfigLoaderSuite extends FunSuite:

  private def decode(raw: String) = ConfigLoader.providerList.decode(None, raw)

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
