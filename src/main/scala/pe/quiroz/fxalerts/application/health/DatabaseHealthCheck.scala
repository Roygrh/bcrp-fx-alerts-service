package pe.quiroz.fxalerts.application.health

/**
 * Puerto de salida: verificación de conectividad con la base de datos.
 *
 * La implementación concreta vive en la capa de infraestructura (doobie). El efecto falla si la
 * base de datos no responde; la interpretación del fallo es responsabilidad de [[HealthService]].
 */
trait DatabaseHealthCheck[F[_]]:
  def ping: F[Unit]
