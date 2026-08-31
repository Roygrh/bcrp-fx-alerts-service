package pe.quiroz.fxalerts.infrastructure.security

import cats.effect.IO
import munit.CatsEffectSuite
import pe.quiroz.fxalerts.application.security.SecretHash

/** Derivación PBKDF2 con pocas iteraciones: la suite prueba el contrato, no la lentitud. */
class Pbkdf2SecretHasherSuite extends CatsEffectSuite:

  private val hasher = Pbkdf2SecretHasher[IO](iterations = 1_000)

  test("un secreto se verifica contra su propio hash y no contra otro secreto"):
    for
      hash  <- hasher.hash("s3cr3t-de-prueba")
      same  <- hasher.verify("s3cr3t-de-prueba", hash)
      other <- hasher.verify("s3cr3t-de-prueba ", hash)
      empty <- hasher.verify("", hash)
    yield
      assert(same)
      assert(!other)
      assert(!empty)

  test("el hash lleva algoritmo, iteraciones, sal y clave derivada, sin el secreto"):
    hasher.hash("secreto-visible").map { hash =>
      val parts = hash.encoded.split(':')
      assertEquals(parts.length, 4)
      assertEquals(parts(0), "pbkdf2-sha256")
      assertEquals(parts(1), "1000")
      assert(!hash.encoded.contains("secreto-visible"))
      assert(Pbkdf2SecretHasher.decode(hash).isRight)
    }

  test("dos hashes del mismo secreto difieren (sal aleatoria) y ambos verifican"):
    for
      first  <- hasher.hash("mismo")
      second <- hasher.hash("mismo")
      a      <- hasher.verify("mismo", first)
      b      <- hasher.verify("mismo", second)
    yield
      assertNotEquals(first.encoded, second.encoded)
      assert(a && b)

  test("la verificación honra las iteraciones del hash almacenado, no las del adaptador"):
    val stored = Pbkdf2SecretHasher.hashWith("secreto", Array.fill[Byte](16)(7), 2_000)
    hasher.verify("secreto", stored).map(assert(_))

  test("un hash ilegible se verifica como no coincidente, sin excepción"):
    val garbage = List(
      "pbkdf2-sha256:1000:sal",
      "argon2id:1000:c2Fs:aGFzaA",
      "pbkdf2-sha256:cero:c2Fs:aGFzaA",
      "pbkdf2-sha256:1000:***:aGFzaA",
      "no es un hash"
    ).map(raw => SecretHash.from(raw).toOption.get)
    for results <- IO.traverse(garbage)(hasher.verify("secreto", _))
    yield
      assertEquals(results, garbage.map(_ => false))
      garbage.foreach(hash => assert(Pbkdf2SecretHasher.decode(hash).isLeft, hash.encoded))

  test("el mensaje de un hash inválido no reproduce el valor recibido"):
    val hash = SecretHash.from("pbkdf2-sha256:1000:c2Fs:VALOR-QUE-NO-DEBE-SALIR!").toOption.get
    assert(Pbkdf2SecretHasher.decode(hash).left.forall(!_.contains("VALOR-QUE-NO-DEBE-SALIR")))
