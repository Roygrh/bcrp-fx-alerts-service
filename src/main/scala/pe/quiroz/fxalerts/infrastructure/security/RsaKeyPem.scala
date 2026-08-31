package pe.quiroz.fxalerts.infrastructure.security

import java.security.interfaces.{RSAPrivateCrtKey, RSAPrivateKey, RSAPublicKey}
import java.security.spec.{PKCS8EncodedKeySpec, RSAPublicKeySpec, X509EncodedKeySpec}
import java.security.KeyFactory
import java.util.Base64
import scala.util.Try

/**
 * Par de claves RSA con el que el servicio firma (privada) y verifica (pública) los tokens.
 *
 * No es una case class y redefine `toString` para que la configuración cargada pueda registrarse o
 * aparecer en un error sin exponer material de claves.
 */
final class SigningKeys(val privateKey: RSAPrivateKey, val publicKey: RSAPublicKey):
  def bits: Int = publicKey.getModulus.bitLength

  override def toString: String = s"SigningKeys(RSA $bits bits)"

/**
 * Lectura de claves RSA en formato PEM desde variables de entorno.
 *
 * Se admiten la clave privada en PKCS#8 (`-----BEGIN PRIVATE KEY-----`, la salida de
 * `openssl genpkey`) y la pública en SubjectPublicKeyInfo (`-----BEGIN PUBLIC KEY-----`). Como una
 * variable de entorno rara vez conserva saltos de línea, el texto puede llevar `\n` literales en su
 * lugar y comillas alrededor; ambos se toleran. Los mensajes de error nunca reproducen el contenido
 * recibido.
 */
object RsaKeyPem:

  /** Tamaño mínimo aceptado: por debajo de 2048 bits RSA no se considera seguro. */
  val minimumBits: Int = 2048

  private val pemBlock = "(?s)-----BEGIN ([A-Z ]+)-----(.*?)-----END [A-Z ]+-----".r

  def privateKey(pem: String): Either[String, RSAPrivateKey] =
    for
      block <- decode(pem, "PRIVATE KEY")
      key   <- Try(
        KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(block))
      ).toOption
        .collect { case key: RSAPrivateKey => key }
        .toRight("La clave privada no es una clave RSA en PKCS#8")
      _ <- checkSize(key.getModulus.bitLength, "privada")
    yield key

  def publicKey(pem: String): Either[String, RSAPublicKey] =
    for
      block <- decode(pem, "PUBLIC KEY")
      key   <- Try(
        KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(block))
      ).toOption
        .collect { case key: RSAPublicKey => key }
        .toRight("La clave pública no es una clave RSA en formato SubjectPublicKeyInfo")
      _ <- checkSize(key.getModulus.bitLength, "pública")
    yield key

  /** La clave pública está contenida en una privada PKCS#8 con parámetros CRT (el caso normal). */
  def publicKeyOf(privateKey: RSAPrivateKey): Either[String, RSAPublicKey] =
    privateKey match
      case crt: RSAPrivateCrtKey =>
        Try(
          KeyFactory
            .getInstance("RSA")
            .generatePublic(new RSAPublicKeySpec(crt.getModulus, crt.getPublicExponent))
        ).toOption
          .collect { case key: RSAPublicKey => key }
          .toRight("No se pudo derivar la clave pública a partir de la privada")
      case _ =>
        Left(
          "La clave privada no contiene el exponente público; indique la clave pública por separado"
        )

  /**
   * Construye el par a partir de la privada y, opcionalmente, la pública. Si se indica la pública,
   * debe corresponder a la privada; si no, se deriva de ella.
   */
  def pair(privatePem: String, publicPem: Option[String]): Either[String, SigningKeys] =
    for
      priv    <- privateKey(privatePem)
      derived <- publicKeyOf(priv)
      pub     <- publicPem.fold(Right(derived): Either[String, RSAPublicKey])(publicKey)
      _       <- Either.cond(
        pub.getModulus == derived.getModulus && pub.getPublicExponent == derived.getPublicExponent,
        (),
        "La clave pública no corresponde a la clave privada"
      )
    yield new SigningKeys(priv, pub)

  private def decode(pem: String, expectedLabel: String): Either[String, Array[Byte]] =
    val text = pem
      .replace("\\n", "\n")
      .trim
      .stripPrefix("\"")
      .stripSuffix("\"")
      .stripPrefix("'")
      .stripSuffix("'")
    val body = pemBlock.findFirstMatchIn(text) match
      case Some(found) if found.group(1) == expectedLabel     => Right(found.group(2))
      case Some(found) if found.group(1) == "RSA PRIVATE KEY" =>
        Left(
          "La clave privada está en PKCS#1 (BEGIN RSA PRIVATE KEY); conviértala a PKCS#8 con " +
            "`openssl pkcs8 -topk8 -nocrypt`"
        )
      case Some(found) if found.group(1) == "ENCRYPTED PRIVATE KEY" =>
        Left("La clave privada está cifrada con contraseña; se requiere sin cifrar (PKCS#8)")
      case Some(found) =>
        Left(s"Se esperaba un bloque PEM 'BEGIN $expectedLabel' y se encontró '${found.group(1)}'")
      case None if text.isEmpty => Left("El valor está vacío")
      case None                 => Left(s"No se encontró un bloque PEM 'BEGIN $expectedLabel'")
    body.flatMap(base64 =>
      Try(Base64.getMimeDecoder.decode(base64)).toOption
        .filter(_.nonEmpty)
        .toRight("El contenido del bloque PEM no es base64")
    )

  private def checkSize(bits: Int, which: String): Either[String, Unit] =
    Either.cond(
      bits >= minimumBits,
      (),
      s"La clave $which tiene $bits bits; se requieren al menos $minimumBits"
    )
