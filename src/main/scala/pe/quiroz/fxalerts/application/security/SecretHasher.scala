package pe.quiroz.fxalerts.application.security

/**
 * Puerto de salida: derivación y verificación de secretos de cliente.
 *
 * El contrato exige que la verificación no revele, ni por su resultado ni por su duración, nada más
 * que si el secreto coincide: la comparación debe hacerse en tiempo constante y un hash ilegible se
 * trata como "no coincide", nunca como excepción.
 */
trait SecretHasher[F[_]]:

  /** Deriva una forma almacenable de un secreto nuevo, con sal propia. */
  def hash(secret: String): F[SecretHash]

  /** Comprueba si el secreto corresponde al hash almacenado. */
  def verify(secret: String, hash: SecretHash): F[Boolean]
