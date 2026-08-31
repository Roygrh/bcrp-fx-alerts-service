package pe.quiroz.fxalerts.infrastructure.security

import cats.effect.Sync
import pe.quiroz.fxalerts.application.security.{SecretHash, SecretHasher}

import java.security.{MessageDigest, SecureRandom}
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import scala.util.Try

/**
 * Adaptador de [[SecretHasher]] con PBKDF2-HMAC-SHA256 (RFC 8018), tal como lo implementa el JDK.
 *
 * Se elige PBKDF2 por tres razones: está en el JDK (sin dependencias nuevas ni código nativo), es
 * una función de derivación pensada para contraseñas (lenta y con sal, a diferencia de un SHA-256 a
 * secas) y los secretos que protege son valores aleatorios de alta entropía generados por
 * [[ClientSecretTool]], no contraseñas humanas, con lo que la resistencia adicional de Argon2id
 * frente a hardware especializado aporta poco aquí. Si los secretos pasaran a elegirlos personas,
 * el cambio a Argon2id se limita a este adaptador: el formato codificado lleva el nombre del
 * algoritmo y los hashes antiguos seguirían verificándose.
 *
 * Formato almacenado, con separador `:` para que el valor pueda vivir en una variable de entorno
 * sin escapes (no contiene `$`, espacios ni comillas):
 * {{{
 *   pbkdf2-sha256:<iteraciones>:<sal base64url>:<clave derivada base64url>
 * }}}
 *
 * La comparación de la clave derivada es en tiempo constante (`MessageDigest.isEqual`). Un hash
 * ilegible se verifica como "no coincide" tras derivar igualmente una clave con parámetros por
 * defecto, para que tampoco esa vía sea distinguible por su duración.
 *
 * @param iterations
 *   iteraciones con las que se derivan los hashes NUEVOS; la verificación usa las que el hash
 *   almacenado declara
 */
final class Pbkdf2SecretHasher[F[_]: Sync](
    iterations: Int = Pbkdf2SecretHasher.defaultIterations
) extends SecretHasher[F]:

  import Pbkdf2SecretHasher.*

  private val random = new SecureRandom()

  def hash(secret: String): F[SecretHash] =
    Sync[F].blocking {
      val salt = new Array[Byte](saltBytes)
      random.nextBytes(salt)
      hashWith(secret, salt, iterations)
    }

  def verify(secret: String, hash: SecretHash): F[Boolean] =
    Sync[F].blocking {
      decode(hash) match
        case Right(parameters) =>
          MessageDigest.isEqual(
            derive(secret, parameters.salt, parameters.iterations),
            parameters.derivedKey
          )
        case Left(_) =>
          val _ = derive(secret, new Array[Byte](saltBytes), iterations)
          false
    }

object Pbkdf2SecretHasher:

  val algorithm: String = "pbkdf2-sha256"

  /** Recomendación de OWASP (2023) para PBKDF2-HMAC-SHA256. */
  val defaultIterations: Int = 600_000

  private val jdkAlgorithm  = "PBKDF2WithHmacSHA256"
  private val keyLengthBits = 256
  private val saltBytes     = 16
  private val encoder       = Base64.getUrlEncoder.withoutPadding
  private val decoder       = Base64.getUrlDecoder

  /**
   * Parámetros y resultado de una derivación, tal como se codifican en el hash almacenado.
   */
  final case class Parameters(iterations: Int, salt: Array[Byte], derivedKey: Array[Byte])

  /** Derivación determinista con sal e iteraciones dadas; base de [[hash]] y de las pruebas. */
  def hashWith(secret: String, salt: Array[Byte], iterations: Int): SecretHash =
    encode(Parameters(iterations, salt, derive(secret, salt, iterations)))

  def encode(parameters: Parameters): SecretHash =
    SecretHash
      .from(
        s"$algorithm:${parameters.iterations}:${encoder.encodeToString(parameters.salt)}:" +
          encoder.encodeToString(parameters.derivedKey)
      )
      .fold(message => throw new IllegalStateException(message), identity)

  /** Interpreta un hash almacenado; el mensaje de error nunca incluye el valor recibido. */
  def decode(hash: SecretHash): Either[String, Parameters] =
    hash.encoded.split(':') match
      case Array(`algorithm`, iterations, salt, derivedKey) =>
        for
          count <- iterations.toIntOption
            .filter(_ >= 1)
            .toRight("El número de iteraciones del hash no es un entero positivo")
          saltBytes <- base64(salt).filter(_.nonEmpty).toRight("La sal del hash no es base64url")
          keyBytes  <- base64(derivedKey)
            .filter(_.nonEmpty)
            .toRight("La clave derivada del hash no es base64url")
        yield Parameters(count, saltBytes, keyBytes)
      case _ =>
        Left(s"El hash debe tener la forma $algorithm:<iteraciones>:<sal>:<clave derivada>")

  private def base64(raw: String): Option[Array[Byte]] = Try(decoder.decode(raw)).toOption

  private def derive(secret: String, salt: Array[Byte], iterations: Int): Array[Byte] =
    val spec = new PBEKeySpec(secret.toCharArray, salt, iterations, keyLengthBits)
    try SecretKeyFactory.getInstance(jdkAlgorithm).generateSecret(spec).getEncoded
    finally spec.clearPassword()
