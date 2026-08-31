package pe.quiroz.fxalerts.application.alert

import pe.quiroz.fxalerts.domain.alert.{AlertStatus, BcrpSeries, CrossingDirection}

/**
 * Datos para registrar una alerta.
 *
 * El cliente propietario no forma parte del comando: es la identidad autenticada de quien lo envía,
 * y la recibe [[AlertService.create]] por separado. El umbral viaja sin validar en su tipo
 * primitivo: la validación ocurre en el dominio ([[pe.quiroz.fxalerts.domain.alert.Alert.create]]),
 * no en quien construye el comando.
 */
final case class CreateAlert(
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
