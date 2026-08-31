package pe.quiroz.fxalerts.application.security

/**
 * Puerto de entrada de la autorización: valida un token de acceso recibido.
 *
 * La validación es completa (formato, firma, vigencia, emisor y audiencia) y sin estado: no se
 * consulta el registro de clientes ni ninguna lista de revocación, por lo que un token es válido
 * hasta que caduca. La vida corta de los tokens acota esa ventana.
 */
trait TokenVerifier[F[_]]:

  def verify(token: String): F[Either[TokenRejection, AuthenticatedClient]]
