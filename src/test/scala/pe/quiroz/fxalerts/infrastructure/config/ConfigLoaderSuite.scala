package pe.quiroz.fxalerts.infrastructure.config

import cats.data.NonEmptyList
import cats.effect.IO
import ciris.ConfigException
import munit.CatsEffectSuite
import pe.quiroz.fxalerts.application.security.Scope
import pe.quiroz.fxalerts.domain.rate.RateProvider
import pe.quiroz.fxalerts.infrastructure.security.{Pbkdf2SecretHasher, TestKeys}

import scala.concurrent.duration.*

/**
 * Carga real de la configuración con un entorno explícito (sin leer el del proceso).
 *
 * A diferencia de [[ConfigDecodersSuite]], aquí se ejercita el cableado completo: que cada variable
 * llegue a su decodificador y que este exista en el momento de usarse. Un decodificador
 * referenciado antes de inicializarse rompía el arranque solo cuando la variable estaba presente;
 * estas pruebas lo habrían detectado. Las claves RSA se generan en tiempo de ejecución.
 */
class ConfigLoaderSuite extends CatsEffectSuite:

  private val secretHash =
    Pbkdf2SecretHasher.hashWith("secreto", Array.fill[Byte](16)(3), 1_000).encoded

  /** Mínimo obligatorio: solo las variables sin valor por defecto. */
  private val required: Map[String, String] = Map(
    "DB_NAME"         -> "bcrp_fx_alerts",
    "DB_USER"         -> "fx_alerts",
    "DB_PASSWORD"     -> "clave-de-prueba",
    "JWT_PRIVATE_KEY" -> TestKeys.singleLine(TestKeys.privatePem(TestKeys.primary)),
    "OAUTH_CLIENTS"   -> s"cliente-001|$secretHash|alerts:read,alerts:write"
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
      assertEquals(config.security.jwt.issuer, "bcrp-fx-alerts-service")
      assertEquals(config.security.jwt.audience, "bcrp-fx-alerts-api")
      assertEquals(config.security.jwt.ttl, 15.minutes)
      assertEquals(config.security.jwt.keys.bits, 2048)
    }

  test("las variables con validación propia se decodifican cuando están presentes"):
    load(
      "BCRP_MAX_RETRIES"   -> "0",
      "ERAPI_MAX_RETRIES"  -> "4",
      "BCRP_LOOKBACK_DAYS" -> "10",
      "BCRP_READ_TIMEOUT"  -> "2500ms",
      "JWT_TTL"            -> "1h",
      "JWT_ISSUER"         -> "emisor",
      "JWT_AUDIENCE"       -> "api"
    ).map { config =>
      assertEquals(config.bcrp.call.maxRetries, 0)
      assertEquals(config.exchangeRateApi.call.maxRetries, 4)
      assertEquals(config.bcrp.lookbackDays, 10)
      assertEquals(config.bcrp.call.readTimeout, 2500.millis)
      assertEquals(config.security.jwt.ttl, 1.hour)
      assertEquals(config.security.jwt.issuer, "emisor")
      assertEquals(config.security.jwt.audience, "api")
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
        assert(error.getMessage.contains("JWT_PRIVATE_KEY"), error.getMessage)
        assert(error.getMessage.contains("OAUTH_CLIENTS"), error.getMessage)
      case other => fail(s"Se esperaba ConfigException: $other")
    }

  // --- Seguridad -------------------------------------------------------------------------------

  test("sin JWT_PRIVATE_KEY el servicio no arranca y el error la nombra"):
    ConfigLoader((required - "JWT_PRIVATE_KEY").get).load[IO].attempt.map {
      case Left(error: ConfigException) =>
        assert(error.getMessage.contains("JWT_PRIVATE_KEY"), error.getMessage)
      case other => fail(s"Se esperaba ConfigException: $other")
    }

  test("el registro de clientes se carga con sus alcances y su hash"):
    load().map { config =>
      val client = config.security.clients.head
      assertEquals(config.security.clients.size, 1)
      assertEquals(client.id.value, "cliente-001")
      assertEquals(client.scopes, Set(Scope.AlertsRead, Scope.AlertsWrite))
      assertEquals(client.secretHash.encoded, secretHash)
    }

  test("JWT_PUBLIC_KEY se acepta si corresponde a la privada y se rechaza si no"):
    for
      matching <- load("JWT_PUBLIC_KEY" -> TestKeys.publicPem(TestKeys.primary))
      mismatch <- loadError("JWT_PUBLIC_KEY" -> TestKeys.publicPem(TestKeys.other))
    yield
      assertEquals(
        matching.security.jwt.keys.publicKey.getModulus,
        TestKeys.primary.publicKey.getModulus
      )
      assert(mismatch.contains("JWT_PUBLIC_KEY"), mismatch)
      assert(mismatch.contains("no corresponde"), mismatch)

  test("una clave privada PKCS#1 impide arrancar con una indicación de cómo convertirla"):
    val pkcs1 = TestKeys.privatePem(TestKeys.primary).replace("PRIVATE KEY", "RSA PRIVATE KEY")
    loadError("JWT_PRIVATE_KEY" -> pkcs1).map { message =>
      assert(message.contains("PKCS#8"), message)
    }

  test("el mensaje de error de una clave inválida no reproduce su contenido"):
    loadError(
      "JWT_PRIVATE_KEY" -> "-----BEGIN PRIVATE KEY-----\nMATERIAL\n-----END PRIVATE KEY-----"
    )
      .map { message =>
        assert(!message.contains("MATERIAL"), message)
      }

  test("JWT_TTL fuera de rango impide arrancar"):
    for
      zero <- loadError("JWT_TTL" -> "0s")
      long <- loadError("JWT_TTL" -> "48h")
    yield
      assert(zero.contains("JWT_TTL"), zero)
      assert(long.contains("JWT_TTL"), long)

  test(
    "OAUTH_CLIENTS con un alcance desconocido, un hash ilegible o ids repetidos impide arrancar"
  ):
    for
      scope <- loadError("OAUTH_CLIENTS" -> s"cliente-001|$secretHash|admin:all")
      hash  <- loadError("OAUTH_CLIENTS" -> "cliente-001|no-es-un-hash|alerts:read")
      dup   <- loadError(
        "OAUTH_CLIENTS" -> s"cliente-001|$secretHash|alerts:read;cliente-001|$secretHash|rates:read"
      )
    yield
      assert(scope.contains("OAUTH_CLIENTS") && scope.contains("admin:all"), scope)
      assert(hash.contains("cliente-001") && !hash.contains("no-es-un-hash"), hash)
      assert(dup.contains("repetidos"), dup)
