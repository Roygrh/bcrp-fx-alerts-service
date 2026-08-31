package pe.quiroz.fxalerts.infrastructure.config

import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.all.*
import ciris.*
import ciris.http4s.*
import com.comcast.ip4s.{host, port, Host, Port}
import org.http4s.Uri
import org.http4s.implicits.uri
import pe.quiroz.fxalerts.domain.rate.RateProvider
import pe.quiroz.fxalerts.infrastructure.config.ConfigDecoders.{atLeast, providerList}

import scala.concurrent.duration.*

/**
 * Describe la configuración de la aplicación a partir de variables de entorno.
 *
 * El origen de las variables se inyecta (`environment`) para que las pruebas puedan cargar la
 * configuración completa con un entorno explícito y determinista; en producción se usa el entorno
 * del proceso ([[ConfigLoader.load]]). Las variables sin valor por defecto son obligatorias; si
 * falta alguna, ciris reporta todas las ausentes en un único error al arrancar. `DB_PASSWORD` se
 * envuelve en [[ciris.Secret]] para que nunca aparezca en mensajes de error ni en `toString`.
 *
 * Todos los miembros son `def`: cada `ConfigValue` es una descripción barata que se construye una
 * vez al cargar, y así ninguno puede observar a otro a medio inicializar, sea cual sea el orden en
 * que se declaren.
 *
 * Las duraciones se escriben con unidad (`500ms`, `5s`, `15m`, `24h`) y se validan al arrancar.
 */
final class ConfigLoader(environment: String => Option[String]):

  /** Variable de entorno leída del origen inyectado, con la misma semántica que `ciris.env`. */
  private def variable(name: String): ConfigValue[Effect, String] =
    ConfigValue.suspend {
      environment(name) match
        case Some(value) => ConfigValue.loaded(ConfigKey.env(name), value)
        case None        => ConfigValue.missing(ConfigKey.env(name))
    }

  private def http: ConfigValue[Effect, HttpConfig] =
    (
      variable("HTTP_HOST").as[Host].default(host"0.0.0.0"),
      variable("HTTP_PORT").as[Port].default(port"8080")
    ).parMapN(HttpConfig.apply)

  private def database: ConfigValue[Effect, DatabaseConfig] =
    (
      variable("DB_HOST").default("localhost"),
      variable("DB_PORT").as[Int].default(5432),
      variable("DB_NAME"),
      variable("DB_SCHEMA").default("fx_alerts"),
      variable("DB_USER"),
      variable("DB_PASSWORD").secret,
      variable("DB_POOL_SIZE").as[Int].default(10)
    ).parMapN(DatabaseConfig.apply)

  /**
   * Política de llamada remota bajo un prefijo (`BCRP_`, `ERAPI_`...), con sus valores por defecto.
   */
  private def remoteCall(
      prefix: String,
      connectTimeout: FiniteDuration,
      readTimeout: FiniteDuration,
      maxRetries: Int,
      retryBackoff: FiniteDuration
  ): ConfigValue[Effect, RemoteCallConfig] =
    (
      variable(s"${prefix}_CONNECT_TIMEOUT").as[FiniteDuration].default(connectTimeout),
      variable(s"${prefix}_READ_TIMEOUT").as[FiniteDuration].default(readTimeout),
      variable(s"${prefix}_MAX_RETRIES").as[Int](using atLeast(0)).default(maxRetries),
      variable(s"${prefix}_RETRY_BACKOFF").as[FiniteDuration].default(retryBackoff)
    ).parMapN(RemoteCallConfig.apply)

  private def bcrp: ConfigValue[Effect, BcrpConfig] =
    (
      variable("BCRP_BASE_URL")
        .as[Uri]
        .default(uri"https://estadisticas.bcrp.gob.pe/estadisticas/series/api"),
      variable("BCRP_LOOKBACK_DAYS").as[Int](using atLeast(1)).default(7),
      remoteCall("BCRP", 3.seconds, 5.seconds, 2, 500.millis)
    ).parMapN(BcrpConfig.apply)

  private def exchangeRateApi: ConfigValue[Effect, ExchangeRateApiConfig] =
    (
      variable("ERAPI_BASE_URL").as[Uri].default(uri"https://open.er-api.com/v6"),
      remoteCall("ERAPI", 3.seconds, 5.seconds, 2, 500.millis)
    ).parMapN(ExchangeRateApiConfig.apply)

  /**
   * `RATE_SOURCES`: códigos de [[RateProvider]] separados por comas, en orden de prioridad
   * (`BCRP,ERAPI` por defecto). `BCRP` a secas desactiva el respaldo; `ERAPI,BCRP` invierte la
   * prioridad. Se rechazan los códigos desconocidos y los repetidos.
   */
  private def rateSources: ConfigValue[Effect, RateSourcesConfig] =
    variable("RATE_SOURCES")
      .as[NonEmptyList[RateProvider]](using providerList)
      .default(NonEmptyList.of(RateProvider.Bcrp, RateProvider.ExchangeRateApi))
      .map(RateSourcesConfig.apply)

  private def rateCache: ConfigValue[Effect, RateCacheConfig] =
    (
      variable("BCRP_CACHE_TTL").as[FiniteDuration].default(15.minutes),
      variable("BCRP_CACHE_MAX_STALE").as[FiniteDuration].default(24.hours),
      variable("BCRP_CACHE_FAILURE_BACKOFF").as[FiniteDuration].default(1.minute)
    ).parMapN(RateCacheConfig.apply).flatMap { config =>
      if config.maxStale >= config.ttl then ConfigValue.default(config)
      else
        ConfigValue.failed(
          ConfigError("BCRP_CACHE_MAX_STALE debe ser mayor o igual que BCRP_CACHE_TTL")
        )
    }

  def config: ConfigValue[Effect, AppConfig] =
    (http, database, bcrp, exchangeRateApi, rateSources, rateCache).parMapN(AppConfig.apply)

  def load[F[_]: Async]: F[AppConfig] = config.load[F]

object ConfigLoader:

  /** Configuración leída del entorno del proceso. */
  def fromProcessEnvironment: ConfigLoader =
    new ConfigLoader(name => Option(System.getenv(name)))

  /** Carga la configuración desde el entorno del proceso. */
  def load[F[_]: Async]: F[AppConfig] = fromProcessEnvironment.load[F]
