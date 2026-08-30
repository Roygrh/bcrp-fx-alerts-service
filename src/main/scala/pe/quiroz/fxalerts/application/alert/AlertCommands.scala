package pe.quiroz.fxalerts.application.alert

import pe.quiroz.fxalerts.domain.alert.{AlertStatus, BcrpSeries, CrossingDirection}

/**
 * Datos para registrar una alerta.
 *
 * Los campos con invariantes (`clientId`, `threshold`) viajan sin validar en su tipo primitivo: la
 * validación ocurre en el dominio ([[pe.quiroz.fxalerts.domain.alert.Alert.create]]), no en quien
 * construye el comando.
 */
final case class CreateAlert(
    clientId: String,
    series: BcrpSeries,
    threshold: BigDecimal,
    direction: CrossingDirection
)

/**
 * Datos para reemplazar la configuración de una alerta existente.
 *
 * Es un reemplazo completo (semántica de `PUT`): el consumidor envía la configuración final y no un
 * parche parcial, lo que evita ambigüedades sobre campos omitidos.
 */
final case class UpdateAlert(
    series: BcrpSeries,
    threshold: BigDecimal,
    direction: CrossingDirection,
    status: AlertStatus
)
