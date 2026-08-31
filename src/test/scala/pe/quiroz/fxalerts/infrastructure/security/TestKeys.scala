package pe.quiroz.fxalerts.infrastructure.security

import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}
import java.security.{Key, KeyPairGenerator}
import java.util.Base64

/**
 * Pares de claves RSA para las pruebas, generados en tiempo de ejecución: ninguna clave se
 * versiona. `primary` y `other` son perezosos y compartidos por toda la ejecución de pruebas, ya
 * que generar un par de 2048 bits cuesta décimas de segundo.
 */
object TestKeys:

  lazy val primary: SigningKeys = generate()

  /** Un segundo par, para firmar tokens que `primary` debe rechazar. */
  lazy val other: SigningKeys = generate()

  def generate(bits: Int = 2048): SigningKeys =
    val generator = KeyPairGenerator.getInstance("RSA")
    generator.initialize(bits)
    val pair = generator.generateKeyPair()
    new SigningKeys(
      pair.getPrivate.asInstanceOf[RSAPrivateKey],
      pair.getPublic.asInstanceOf[RSAPublicKey]
    )

  /** PEM PKCS#8 con saltos de línea reales, como lo escribe `openssl genpkey`. */
  def privatePem(keys: SigningKeys): String = pem("PRIVATE KEY", keys.privateKey)

  /**
   * PEM SubjectPublicKeyInfo con saltos de línea reales, como lo escribe `openssl pkey -pubout`.
   */
  def publicPem(keys: SigningKeys): String = pem("PUBLIC KEY", keys.publicKey)

  /** Forma en una sola línea con `\n` literales, como se escribe en un archivo `.env`. */
  def singleLine(pem: String): String = pem.replace("\n", "\\n")

  private def pem(label: String, key: Key): String =
    val body = Base64.getMimeEncoder(64, "\n".getBytes).encodeToString(key.getEncoded)
    s"-----BEGIN $label-----\n$body\n-----END $label-----\n"
