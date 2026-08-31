package pe.quiroz.fxalerts.infrastructure.config

import cats.data.NonEmptyList
import cats.effect.IO
import ciris.ConfigException
import munit.CatsEffectSuite
import pe.quiroz.fxalerts.domain.rate.RateProvider

import scala.concurrent.duration.*

/**
 * Carga real de la configuración con un entorno explícito (sin leer el del proceso).
 *
 * A diferencia de [[ConfigDecodersSuite]], aquí se ejercita el cableado completo: que cada variable
 * llegue a su decodificador y que este exista en el momento de usarse. Un decodificador
 * referenciado antes de inicializarse rompía el arranque solo cuando la variable estaba presente;
 * estas pruebas lo habrían detectado.
 */
class ConfigLoaderSuite extends CatsEffectSuite:

  /** Mínimo obligatorio: solo las variables sin valor por defecto. */
  private val required: Map[String, String] = Map(
    "DB_NAME"     -> "bcrp_fx_alerts",
    "DB_USER"     -> "fx_alerts",
    "DB_PASSWORD" -> "clave-de-prueba"
  )

  private def load(extra: (String, String)*): IO[AppConfig] =
    ConfigLoader((required ++ extra).get).load[IO]

  private def loadError(extra: (String, String)*): IO[String] =
    load(extra*).attempt.map {
      case Left(error: ConfigException) => error.getMessage
      case Left(other)                  => fail(s"Se esperaba ConfigException: $other")
      case Right(config)                => fail(s"Se esperaba un error de configuración: $config")
    }

  test("RATE_SOURCES presente con un valor válido fija el orden de las fuentes"):
    load("RATE_SOURCES" -> "ERAPI,BCRP").map { config =>
      assertEquals(
        config.rateSources.order,
        NonEmptyList.of(RateProvider.ExchangeRateApi, RateProvider.Bcrp)
      )
    }

  test("RATE_SOURCES con una sola fuente desactiva el respaldo"):
    load("RATE_SOURCES" -> "BCRP").map { config =>
      assertEquals(config.rateSources.order, NonEmptyList.one(RateProvider.Bcrp))
    }

  test("RATE_SOURCES con un código desconocido impide arrancar y nombra la variable"):
    loadError("RATE_SOURCES" -> "BCRP,SUNAT").map { message =>
      assert(message.contains("RATE_SOURCES"), message)
      assert(message.contains("desconocido"), message)
      assert(message.contains("SUNAT"), message)
    }

  test("RATE_SOURCES ausente aplica el orden por defecto BCRP > ERAPI"):
    load().map { config =>
      assertEquals(
        config.rateSources.order,
        NonEmptyList.of(RateProvider.Bcrp, RateProvider.ExchangeRateApi)
      )
    }

  test("los valores por defecto del resto de secciones se aplican con el mínimo obligatorio"):
    load().map { config =>
      assertEquals(config.http.port.value, 8080)
      assertEquals(config.database.port, 5432)
      assertEquals(config.database.password.value, "clave-de-prueba")
      assertEquals(config.bcrp.lookbackDays, 7)
      assertEquals(config.bcrp.call.maxRetries, 2)
      assertEquals(config.exchangeRateApi.call.attemptTimeout, 8.seconds)
      assertEquals(config.rateCache.ttl, 15.minutes)
    }

  test("las variables con validación propia se decodifican cuando están presentes"):
    load(
      "BCRP_MAX_RETRIES"   -> "0",
      "ERAPI_MAX_RETRIES"  -> "4",
      "BCRP_LOOKBACK_DAYS" -> "10",
      "BCRP_READ_TIMEOUT"  -> "2500ms"
    ).map { config =>
      assertEquals(config.bcrp.call.maxRetries, 0)
      assertEquals(config.exchangeRateApi.call.maxRetries, 4)
      assertEquals(config.bcrp.lookbackDays, 10)
      assertEquals(config.bcrp.call.readTimeout, 2500.millis)
    }

  test("un reintento negativo impide arrancar y nombra la variable"):
    loadError("ERAPI_MAX_RETRIES" -> "-1").map { message =>
      assert(message.contains("ERAPI_MAX_RETRIES"), message)
    }

  test("una caché con maxStale menor que ttl impide arrancar"):
    loadError("BCRP_CACHE_TTL" -> "1h", "BCRP_CACHE_MAX_STALE" -> "5m").map { message =>
      assert(message.contains("BCRP_CACHE_MAX_STALE"), message)
    }

  test("faltando las variables obligatorias se reportan todas en un único error"):
    ConfigLoader(Map.empty[String, String].get).load[IO].attempt.map {
      case Left(error: ConfigException) =>
        assert(error.getMessage.contains("DB_NAME"), error.getMessage)
        assert(error.getMessage.contains("DB_USER"), error.getMessage)
        assert(error.getMessage.contains("DB_PASSWORD"), error.getMessage)
      case other => fail(s"Se esperaba ConfigException: $other")
    }
