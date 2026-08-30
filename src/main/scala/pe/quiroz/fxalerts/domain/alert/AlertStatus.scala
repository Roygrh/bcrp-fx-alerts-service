package pe.quiroz.fxalerts.domain.alert

/**
 * Estado de una alerta.
 *
 * Una alerta inactiva se conserva con su configuración pero no se evalúa contra el tipo de cambio;
 * el cliente puede reactivarla sin volver a registrarla.
 */
enum AlertStatus:
  case Active, Inactive
