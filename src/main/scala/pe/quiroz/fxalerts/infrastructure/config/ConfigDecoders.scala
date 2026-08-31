package pe.quiroz.fxalerts.infrastructure.config

import cats.data.NonEmptyList
import cats.syntax.all.*
import ciris.{ConfigDecoder, ConfigError}
import pe.quiroz.fxalerts.domain.rate.RateProvider

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
