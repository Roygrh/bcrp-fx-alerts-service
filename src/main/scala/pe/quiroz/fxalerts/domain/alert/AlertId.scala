package pe.quiroz.fxalerts.domain.alert

import java.util.UUID

/**
 * Identificador único de una alerta.
 *
 * Es un tipo opaco sobre `UUID`: no tiene coste en tiempo de ejecución y evita que un `UUID`
 * cualquiera (o el identificador de otra entidad) se pase por error donde se espera una alerta. No
 * tiene invariantes propias, por lo que su constructor es público; la generación de valores nuevos
 * corresponde a la capa de aplicación, que dispone del efecto necesario.
 */
opaque type AlertId = UUID

object AlertId:
  def apply(value: UUID): AlertId = value

  extension (id: AlertId) def value: UUID = id
