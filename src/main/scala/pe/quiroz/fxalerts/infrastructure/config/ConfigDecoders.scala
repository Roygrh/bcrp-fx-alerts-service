package pe.quiroz.fxalerts.infrastructure.config

import cats.data.NonEmptyList
import cats.syntax.all.*
import ciris.{ConfigDecoder, ConfigError}
import pe.quiroz.fxalerts.application.security.{RegisteredClient, Scope, SecretHash}
import pe.quiroz.fxalerts.domain.alert.ClientId
import pe.quiroz.fxalerts.domain.rate.RateProvider
import pe.quiroz.fxalerts.infrastructure.security.Pbkdf2SecretHasher

import scala.concurrent.duration.FiniteDuration

/**
 * Decodificadores propios de la configuración.
 *
 * Viven en un objeto aparte de [[ConfigLoader]] a propósito: un `object` se inicializa completo la
 * primera vez que se accede a cualquiera de sus miembros, de modo que ningún valor de configuración
 * puede observar un decodificador todavía nulo, sea cual sea el orden de declaración en el archivo
 * que los usa. (Un `val` de un mismo objeto referenciado antes de su declaración vale `null` hasta
 * que el constructor llega a él; con `.default(...)` el fallo solo se manifiesta cuando la variable
 * de entorno está presente, que es exactamente cuando el decodificador se invoca.)
 */
private[config] object ConfigDecoders:

  /** Entero mayor o igual que `minimum`; el mensaje de error nombra la variable. */
  def atLeast(minimum: Int): ConfigDecoder[String, Int] =
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

  /** Duración estrictamente positiva y acotada por `maximum`; el mensaje nombra la variable. */
  def durationBetween(
      minimum: FiniteDuration,
      maximum: FiniteDuration
  ): ConfigDecoder[String, FiniteDuration] =
    ConfigDecoder[String, FiniteDuration].mapEither { (key, value) =>
      Either.cond(
        value >= minimum && value <= maximum,
        value,
        ConfigError(
          s"${key.fold("La duración")(_.description)} debe estar entre $minimum y $maximum " +
            s"(recibido: $value)"
        )
      )
    }

  /**
   * Lista no vacía de códigos de [[RateProvider]] separados por comas, en orden, sin repetidos y
   * sin distinguir mayúsculas de minúsculas.
   */
  val providerList: ConfigDecoder[String, NonEmptyList[RateProvider]] =
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

  /**
   * Identificadores de cliente admitidos en el registro: más estrictos que [[ClientId]] porque
   * viajan en la cabecera Basic (sin `:`), en el log y en esta misma variable (sin separadores).
   */
  private val clientIdFormat = "^[A-Za-z0-9._-]{1,64}$".r

  /**
   * Registro de clientes: entradas separadas por `;`, cada una con tres campos separados por `|`:
   * {{{
   *   <client_id>|<hash del secreto>|<alcance>[,<alcance>...]
   * }}}
   * Se rechazan los identificadores repetidos o con caracteres fuera de `[A-Za-z0-9._-]`, los
   * hashes que el adaptador no sabe interpretar y los alcances desconocidos. Los mensajes de error
   * nombran el cliente afectado pero nunca reproducen el hash.
   */
  val clientList: ConfigDecoder[String, NonEmptyList[RegisteredClient]] =
    ConfigDecoder[String, String].mapEither { (key, raw) =>
      val name    = key.fold("El registro de clientes")(_.description)
      val entries = raw.split(';').toList.map(_.trim).filter(_.nonEmpty)
      for
        clients  <- entries.traverse(entry => registeredClient(name, entry))
        nonEmpty <- NonEmptyList
          .fromList(clients)
          .toRight(ConfigError(s"$name no puede estar vacío"))
        _ <- Either.cond(
          nonEmpty.map(_.id).toList.distinct.size == nonEmpty.size,
          (),
          ConfigError(s"$name contiene identificadores de cliente repetidos")
        )
      yield nonEmpty
    }

  private def registeredClient(name: String, entry: String): Either[ConfigError, RegisteredClient] =
    entry.split('|').map(_.trim) match
      case Array(rawId, rawHash, rawScopes) =>
        for
          id <- Either.cond(
            clientIdFormat.matches(rawId),
            rawId,
            ConfigError(
              s"$name contiene un identificador de cliente no admitido (se admite [A-Za-z0-9._-], " +
                "hasta 64 caracteres)"
            )
          )
          clientId <- ClientId.from(id).left.map(error => ConfigError(s"$name: ${error.message}"))
          hash     <- SecretHash
            .from(rawHash)
            .flatMap(hash => Pbkdf2SecretHasher.decode(hash).as(hash))
            .left
            .map(message => ConfigError(s"$name, cliente '$id': $message"))
          scopes <- rawScopes
            .split(',')
            .toList
            .map(_.trim)
            .filter(_.nonEmpty)
            .traverse(value =>
              Scope
                .fromValue(value)
                .toRight(
                  ConfigError(
                    s"$name, cliente '$id': alcance desconocido '$value'; se admite: " +
                      Scope.values.map(_.value).mkString(", ")
                  )
                )
            )
          _ <- Either.cond(
            scopes.nonEmpty,
            (),
            ConfigError(s"$name, cliente '$id': debe tener al menos un alcance")
          )
        yield RegisteredClient(clientId, hash, scopes.toSet)
      case _ =>
        Left(
          ConfigError(
            s"$name: cada entrada debe tener la forma <client_id>|<hash>|<alcance,...>, " +
              "separadas entre sí por ';'"
          )
        )
