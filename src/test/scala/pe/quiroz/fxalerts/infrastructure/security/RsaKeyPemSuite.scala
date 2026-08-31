package pe.quiroz.fxalerts.infrastructure.security

import munit.FunSuite

/** Lectura de claves PEM tal como llegan por variables de entorno. */
class RsaKeyPemSuite extends FunSuite:

  private val keys = TestKeys.primary

  test("lee la clave privada PKCS#8 y la pública SPKI con saltos de línea reales"):
    val priv = RsaKeyPem.privateKey(TestKeys.privatePem(keys))
    val pub  = RsaKeyPem.publicKey(TestKeys.publicPem(keys))
    assertEquals(priv.map(_.getModulus), Right(keys.privateKey.getModulus))
    assertEquals(pub.map(_.getModulus), Right(keys.publicKey.getModulus))

  test("acepta la forma de una sola línea con \\n literales y comillas, como en un .env"):
    val quoted = "'" + TestKeys.singleLine(TestKeys.privatePem(keys)) + "'"
    assertEquals(
      RsaKeyPem.privateKey(quoted).map(_.getModulus),
      Right(keys.privateKey.getModulus)
    )
    val doubleQuoted = "\"" + TestKeys.singleLine(TestKeys.publicPem(keys)) + "\""
    assertEquals(
      RsaKeyPem.publicKey(doubleQuoted).map(_.getModulus),
      Right(keys.publicKey.getModulus)
    )

  test("deriva la clave pública de la privada y acepta la pública indicada si corresponde"):
    val derived  = RsaKeyPem.pair(TestKeys.privatePem(keys), None)
    val provided = RsaKeyPem.pair(TestKeys.privatePem(keys), Some(TestKeys.publicPem(keys)))
    assertEquals(derived.map(_.publicKey.getModulus), Right(keys.publicKey.getModulus))
    assertEquals(provided.map(_.publicKey.getModulus), Right(keys.publicKey.getModulus))

  test("rechaza una clave pública que no corresponde a la privada"):
    val result = RsaKeyPem.pair(TestKeys.privatePem(keys), Some(TestKeys.publicPem(TestKeys.other)))
    assert(result.left.exists(_.contains("no corresponde")), result.toString)

  test("rechaza una clave privada PKCS#1 indicando cómo convertirla"):
    val pkcs1  = TestKeys.privatePem(keys).replace("PRIVATE KEY", "RSA PRIVATE KEY")
    val result = RsaKeyPem.privateKey(pkcs1)
    assert(result.left.exists(_.contains("PKCS#8")), result.toString)
    assert(result.left.exists(_.contains("openssl pkcs8")), result.toString)

  test("rechaza una clave demasiado corta nombrando el mínimo"):
    val short  = TestKeys.generate(bits = 1024)
    val result = RsaKeyPem.privateKey(TestKeys.privatePem(short))
    assert(result.left.exists(_.contains("2048")), result.toString)

  test("rechaza valores vacíos, sin bloque PEM o con base64 corrupto sin reproducirlos"):
    assert(RsaKeyPem.privateKey("").isLeft)
    assert(RsaKeyPem.privateKey("MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQ").isLeft)
    val corrupt = "-----BEGIN PRIVATE KEY-----\nSECRETO-CORRUPTO\n-----END PRIVATE KEY-----"
    val result  = RsaKeyPem.privateKey(corrupt)
    assert(result.isLeft)
    assert(result.left.forall(!_.contains("SECRETO-CORRUPTO")))

  test("SigningKeys no expone material de claves en toString"):
    assertEquals(keys.toString, "SigningKeys(RSA 2048 bits)")
