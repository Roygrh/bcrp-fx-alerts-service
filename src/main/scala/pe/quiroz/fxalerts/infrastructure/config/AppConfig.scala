package pe.quiroz.fxalerts.infrastructure.config

import cats.data.NonEmptyList
import ciris.Secret
import com.comcast.ip4s.{Host, Port}
import org.http4s.Uri
import pe.quiroz.fxalerts.application.security.RegisteredClient
import pe.quiroz.fxalerts.domain.rate.RateProvider
import pe.quiroz.fxalerts.infrastructure.security.SigningKeys

import scala.concurrent.duration.FiniteDuration

final case class HttpConfig(host: Host, port: Port)

final case class DatabaseConfig(
    host: String,
    port: Int,
    name: String,
    schema: String,
    user: String,
    password: Secret[String],
    poolSize: Int
):
  def jdbcUrl: String = s"jdbc:postgresql://$host:$port/$name"

/**
 * Política de llamada a un servicio remoto, común a todos los adaptadores HTTP.
 *
 * @param connectTimeout
 *   tiempo máximo para establecer la conexión
 * @param readTimeout
 *   tiempo máximo de espera por la respuesta una vez conectados
 * @param maxRetries
 *   reintentos adicionales al primer intento ante fallos transitorios (0 desactiva los reintentos)
 * @param retryBackoff
 *   espera antes del primer reintento; se duplica en cada reintento posterior
 */
final case class RemoteCallConfig(
    connectTimeout: FiniteDuration,
    readTimeout: FiniteDuration,
    maxRetries: Int,
    retryBackoff: FiniteDuration
):
  /** Presupuesto total de un intento: conexión más lectura. */
  def attemptTimeout: FiniteDuration = connectTimeout + readTimeout

/**
 * Parámetros del cliente hacia la API de series del BCRP (fuente oficial).
 *
 * @param baseUri
 *   raíz de la API; los segmentos de serie, formato, fechas e idioma se añaden por petición
 * @param lookbackDays
 *   días hacia atrás que abarca la ventana consultada; debe cubrir el mayor tramo previsible de
 *   días sin dato (fin de semana largo con feriado)
 */
final case class BcrpConfig(baseUri: Uri, lookbackDays: Int, call: RemoteCallConfig)

/**
 * Parámetros del cliente hacia ExchangeRate-API (fuente de respaldo, no oficial).
 *
 * @param baseUri
 *   raíz de la API; el recurso `latest/USD` se añade por petición
 */
final case class ExchangeRateApiConfig(baseUri: Uri, call: RemoteCallConfig)

/**
 * Orden en que se consultan las fuentes de tipo de cambio: la primera es la principal y las
 * siguientes solo se consultan cuando la anterior no pudo responder.
 */
final case class RateSourcesConfig(order: NonEmptyList[RateProvider])

/**
 * Parámetros de la caché en memoria del tipo de cambio.
 *
 * @param ttl
 *   periodo durante el cual un dato obtenido de la fuente se considera vigente y no se reconsulta
 * @param maxStale
 *   antigüedad máxima (desde que la fuente entregó el dato) con la que se sirve un valor obsoleto
 *   cuando la fuente no responde; debe ser mayor o igual que `ttl`
 * @param failureBackoff
 *   tras un fallo de la fuente, tiempo durante el que se sirve el valor obsoleto (o se responde el
 *   mismo fallo si no hay valor) sin volver a intentar la consulta, para que cada petición no pague
 *   el coste completo de los reintentos
 */
final case class RateCacheConfig(
    ttl: FiniteDuration,
    maxStale: FiniteDuration,
    failureBackoff: FiniteDuration
)

/**
 * Parámetros de los tokens de acceso.
 *
 * @param keys
 *   par RSA con el que se firman (privada) y verifican (pública) los tokens
 * @param issuer
 *   valor del claim `iss`, exigido también al verificar
 * @param audience
 *   valor del claim `aud`, exigido también al verificar
 * @param ttl
 *   vida de cada token desde su emisión
 */
final case class JwtConfig(keys: SigningKeys, issuer: String, audience: String, ttl: FiniteDuration)

/**
 * Seguridad de la API: cómo se firman los tokens y qué clientes pueden obtenerlos.
 *
 * @param clients
 *   clientes registrados, con su secreto ya derivado y sus alcances concedidos
 */
final case class SecurityConfig(jwt: JwtConfig, clients: NonEmptyList[RegisteredClient])

final case class AppConfig(
    http: HttpConfig,
    database: DatabaseConfig,
    bcrp: BcrpConfig,
    exchangeRateApi: ExchangeRateApiConfig,
    rateSources: RateSourcesConfig,
    rateCache: RateCacheConfig,
    security: SecurityConfig
)
