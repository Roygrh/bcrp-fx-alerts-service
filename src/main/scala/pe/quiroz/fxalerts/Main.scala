package pe.quiroz.fxalerts

import cats.effect.{IO, IOApp, Resource}
import org.http4s.server.Server
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import pe.quiroz.fxalerts.application.alert.{AlertEvaluationService, AlertService}
import pe.quiroz.fxalerts.application.health.HealthService
import pe.quiroz.fxalerts.application.rate.ExchangeRateService
import pe.quiroz.fxalerts.application.security.{TokenPolicy, TokenService}
import pe.quiroz.fxalerts.domain.rate.RateProvider
import pe.quiroz.fxalerts.infrastructure.bcrp.BcrpExchangeRateClient
import pe.quiroz.fxalerts.infrastructure.cache.CachedExchangeRateSource
import pe.quiroz.fxalerts.infrastructure.config.{AppConfig, ConfigLoader}
import pe.quiroz.fxalerts.infrastructure.erapi.ExchangeRateApiClient
import pe.quiroz.fxalerts.infrastructure.http.alert.AlertRoutes
import pe.quiroz.fxalerts.infrastructure.http.auth.{BearerAuthentication, TokenRoutes}
import pe.quiroz.fxalerts.infrastructure.http.health.HealthRoutes
import pe.quiroz.fxalerts.infrastructure.http.rate.RateRoutes
import pe.quiroz.fxalerts.infrastructure.http.{HttpApi, HttpServer}
import pe.quiroz.fxalerts.infrastructure.persistence.{
  Database,
  DoobieAlertRepository,
  DoobieDatabaseHealthCheck,
  FlywayMigrator
}
import pe.quiroz.fxalerts.infrastructure.rate.{FallbackExchangeRateSource, ProviderSource}
import pe.quiroz.fxalerts.infrastructure.remote.RemoteHttpClient
import pe.quiroz.fxalerts.infrastructure.security.jwt.{JwtTokens, Rs256Jwt}
import pe.quiroz.fxalerts.infrastructure.security.{Pbkdf2SecretHasher, StaticClientRegistry}

import scala.concurrent.duration.*

object Main extends IOApp.Simple:

  private given Logger[IO] = Slf4jLogger.getLogger[IO]

  private val databaseHealthTimeout: FiniteDuration = 2.seconds

  /**
   * Tiempo que la verificación de salud espera al tipo de cambio. Con la caché vigente responde al
   * instante; con la caché vacía la consulta a las fuentes continúa en segundo plano aunque este
   * plazo se agote, y la siguiente verificación la encontrará resuelta.
   */
  private val rateSourceHealthTimeout: FiniteDuration = 3.seconds

  def run: IO[Unit] =
    ConfigLoader.load[IO].flatMap(config => application(config).useForever)

  private def application(config: AppConfig): Resource[IO, Server] =
    for
      transactor <- Database.transactor[IO](config.database)
      _          <- Resource.eval(FlywayMigrator.migrate[IO](transactor, config.database.schema))
      sources    <- config.rateSources.order.traverse(rateSource(config, _))
      _          <- Resource.eval(
        Logger[IO].info(
          "Fuentes de tipo de cambio, en orden de prioridad: " +
            sources
              .map(s => s"${s.provider.code}${if s.provider.official then "" else " (no oficial)"}")
              .toList
              .mkString(" > ")
        )
      )
      rateSource <- CachedExchangeRateSource.resource[IO](
        FallbackExchangeRateSource[IO](sources),
        config.rateCache
      )
      rateService   = ExchangeRateService[IO](rateSource)
      healthService = HealthService[IO](
        DoobieDatabaseHealthCheck[IO](transactor),
        databaseHealthTimeout,
        rateService,
        rateSourceHealthTimeout
      )
      alertRepository = DoobieAlertRepository[IO](transactor)
      alertService    = AlertService[IO](alertRepository)
      evaluation      = AlertEvaluationService[IO](alertRepository, rateService)
      jwt             = config.security.jwt
      tokens          = JwtTokens[IO](Rs256Jwt(jwt.keys, jwt.issuer, jwt.audience))
      registry        = StaticClientRegistry[IO](config.security.clients)
      tokenService <- Resource.eval(
        TokenService[IO](
          registry,
          Pbkdf2SecretHasher[IO](),
          tokens,
          TokenPolicy(jwt.issuer, jwt.audience, jwt.ttl)
        )
      )
      _ <- Resource.eval(
        Logger[IO].info(
          s"Seguridad: ${registry.size} cliente(s) registrado(s); tokens RS256 (${jwt.keys.bits} " +
            s"bits) emitidos por '${jwt.issuer}' para '${jwt.audience}' con vida ${jwt.ttl}"
        )
      )
      auth    = BearerAuthentication[IO](tokens)
      httpApp = HttpApi.httpApp[IO](
        HealthRoutes[IO](healthService),
        TokenRoutes[IO](tokenService),
        AlertRoutes[IO](alertService, evaluation, auth),
        RateRoutes[IO](rateService, auth),
        auth
      )
      server <- HttpServer.resource[IO](config.http, httpApp)
      _      <- Resource.eval(
        Logger[IO].info(
          s"Servidor HTTP escuchando en http://${config.http.host}:${config.http.port} " +
            "(documentación en /docs)"
        )
      )
    yield server

  /**
   * Construye el adaptador de cada fuente configurada, con su propio cliente HTTP. Solo se
   * instancian las fuentes presentes en `RATE_SOURCES`.
   */
  private def rateSource(
      config: AppConfig,
      provider: RateProvider
  ): Resource[IO, ProviderSource[IO]] =
    provider match
      case RateProvider.Bcrp =>
        RemoteHttpClient
          .resource[IO](config.bcrp.call)
          .map(client => ProviderSource(provider, BcrpExchangeRateClient[IO](client, config.bcrp)))
      case RateProvider.ExchangeRateApi =>
        RemoteHttpClient
          .resource[IO](config.exchangeRateApi.call)
          .evalMap(client => ExchangeRateApiClient[IO](client, config.exchangeRateApi))
          .map(ProviderSource(provider, _))
