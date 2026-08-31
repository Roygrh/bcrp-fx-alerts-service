package pe.quiroz.fxalerts.application.security

/** Puerto de salida: serializa y firma un token de acceso a partir de sus claims. */
trait TokenIssuer[F[_]]:

  /** Devuelve el token en su forma compacta, lista para viajar como `Bearer`. */
  def sign(claims: AccessTokenClaims): F[String]
