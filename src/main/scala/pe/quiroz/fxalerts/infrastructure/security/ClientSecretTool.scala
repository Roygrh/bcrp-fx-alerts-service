package pe.quiroz.fxalerts.infrastructure.security

import cats.effect.{IO, IOApp}

import java.security.SecureRandom
import java.util.Base64

/**
 * Utilidad de línea de comandos para dar de alta clientes: genera un secreto aleatorio y su hash, o
 * calcula el hash de un secreto ya existente.
 *
 * {{{
 *   sbt "runMain pe.quiroz.fxalerts.infrastructure.security.ClientSecretTool"
 *   CLIENT_SECRET=... sbt "runMain pe.quiroz.fxalerts.infrastructure.security.ClientSecretTool"
 * }}}
 *
 * El secreto se lee de la variable `CLIENT_SECRET` y no de un argumento, para que no quede en el
 * historial de la consola. Cuando se genera, se muestra una única vez: el servicio solo conserva el
 * hash y no hay forma de recuperarlo después.
 */
object ClientSecretTool extends IOApp.Simple:

  private val secretBytes = 32

  def run: IO[Unit] =
    for
      provided <- IO(sys.env.get("CLIENT_SECRET").map(_.trim).filter(_.nonEmpty))
      secret   <- provided.fold(generate)(IO.pure)
      hash     <- Pbkdf2SecretHasher[IO]().hash(secret)
      _        <- IO.println("")
      _        <- provided.fold(
        IO.println(s"client_secret (entregar al cliente; no se vuelve a mostrar):\n  $secret\n")
      )(_ => IO.println("Hash del secreto recibido en CLIENT_SECRET:\n"))
      _ <- IO.println(
        s"hash (campo 2 de la entrada del cliente en OAUTH_CLIENTS):\n  ${hash.encoded}"
      )
      _ <- IO.println("")
    yield ()

  /** 256 bits de una fuente criptográfica, en base64url sin relleno (43 caracteres). */
  private def generate: IO[String] =
    IO {
      val bytes = new Array[Byte](secretBytes)
      new SecureRandom().nextBytes(bytes)
      Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
    }
