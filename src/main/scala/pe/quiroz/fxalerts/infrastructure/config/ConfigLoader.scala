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

import scala.concurrent.duration.*

/**
 * Carga la configuración exclusivamente desde variables de entorno.
 *
 * Las variables sin valor por defecto son obligatorias; si falta alguna, ciris reporta todas las
 * ausentes en un único error al arrancar. `DB_PASSWORD` se envuelve en [[ciris.Secret]] para que
 * nunca aparezca en mensajes de error ni en `toString`.
 *
 * Las duraciones se escriben con unidad (`500ms`, `5s`, `15m`, `24h`) y se validan al arrancar.
 */
object ConfigLoader:

  private val http: ConfigValue[Effect, HttpConfig] =
    (
      env("HTTP_HOST").as[Host].default(host"0.0.0.0"),
      env("HTTP_PORT").as[Port].default(port"8080")
    ).parMapN(HttpConfig.apply)

  private val database: ConfigValue[Effect, DatabaseConfig] =
    (
      env("DB_HOST").default("localhost"),
      env("DB_PORT").as[Int].default(5432),
      env("DB_NAME"),
      env("DB_SCHEMA").default("fx_alerts"),
      env("DB_USER"),
      env("DB_PASSWORD").secret,
      env("DB_POOL_SIZE").as[Int].default(10)
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
      env(s"${prefix}_CONNECT_TIMEOUT").as[FiniteDuration].default(connectTimeout),
      env(s"${prefix}_READ_TIMEOUT").as[FiniteDuration].default(readTimeout),
      env(s"${prefix}_MAX_RETRIES").as[Int](using atLeast(0)).default(maxRetries),
      env(s"${prefix}_RETRY_BACKOFF").as[FiniteDuration].default(retryBackoff)
    ).parMapN(RemoteCallConfig.apply)

  private val bcrp: ConfigValue[Effect, BcrpConfig] =
    (
      env("BCRP_BASE_URL")
        .as[Uri]
        .default(uri"https://estadisticas.bcrp.gob.pe/estadisticas/series/api"),
      env("BCRP_LOOKBACK_DAYS").as[Int](using atLeast(1)).default(7),
      remoteCall("BCRP", 3.seconds, 5.seconds, 2, 500.millis)
    ).parMapN(BcrpConfig.apply)

  private val exchangeRateApi: ConfigValue[Effect, ExchangeRateApiConfig] =
    (
      env("ERAPI_BASE_URL").as[Uri].default(uri"https://open.er-api.com/v6"),
      remoteCall("ERAPI", 3.seconds, 5.seconds, 2, 500.millis)
    ).parMapN(ExchangeRateApiConfig.apply)

  /**
   * `RATE_SOURCES`: códigos de [[RateProvider]] separados por comas, en orden de prioridad
   * (`BCRP,ERAPI` por defecto). `BCRP` a secas desactiva el respaldo; `ERAPI,BCRP` invierte la
   * prioridad. Se rechazan los códigos desconocidos y los repetidos.
   */
  private val rateSources: ConfigValue[Effect, RateSourcesConfig] =
    env("RATE_SOURCES")
      .as[NonEmptyList[RateProvider]](using providerList)
      .default(NonEmptyList.of(RateProvider.Bcrp, RateProvider.ExchangeRateApi))
      .map(RateSourcesConfig.apply)

  private val rateCache: ConfigValue[Effect, RateCacheConfig] =
    (
      env("BCRP_CACHE_TTL").as[FiniteDuration].default(15.minutes),
      env("BCRP_CACHE_MAX_STALE").as[FiniteDuration].default(24.hours),
      env("BCRP_CACHE_FAILURE_BACKOFF").as[FiniteDuration].default(1.minute)
    ).parMapN(RateCacheConfig.apply).flatMap { config =>
      if config.maxStale >= config.ttl then ConfigValue.default(config)
      else
        ConfigValue.failed(
          ConfigError("BCRP_CACHE_MAX_STALE debe ser mayor o igual que BCRP_CACHE_TTL")
        )
    }

  val config: ConfigValue[Effect, AppConfig] =
    (http, database, bcrp, exchangeRateApi, rateSources, rateCache).parMapN(AppConfig.apply)

  def load[F[_]: Async]: F[AppConfig] = config.load[F]

  private def atLeast(minimum: Int): ConfigDecoder[String, Int] =
    ConfigDecoder[String, Int].mapEither { (key, value) =>
      Either.cond(
        value >= minimum,
        value,
        ConfigError(
          s"${key.fold("El valor")(_.description)} debe ser mayor o igual que $minimum " +
            s"(recibido: $value)"
        )
      )
    }

  private[config] val providerList: ConfigDecoder[String, NonEmptyList[RateProvider]] =
    ConfigDecoder[String, String].mapEither { (key, raw) =>
      val name     = key.fold("La lista de fuentes")(_.description)
      val admitted = RateProvider.values.map(_.code).mkString(", ")
      val codes    = raw.split(',').toList.map(_.trim).filter(_.nonEmpty)
      for
        providers <- codes.traverse(code =>
          RateProvider
            .fromCode(code)
            .toRight(
              ConfigError(s"$name contiene un código desconocido: '$code'; se admite: $admitted")
            )
        )
        nonEmpty <- NonEmptyList
          .fromList(providers)
          .toRight(ConfigError(s"$name no puede estar vacía; se admite: $admitted"))
        _ <- Either.cond(
          nonEmpty.toList.distinct.size == nonEmpty.size,
          (),
          ConfigError(s"$name contiene códigos repetidos: $raw")
        )
      yield nonEmpty
    }
