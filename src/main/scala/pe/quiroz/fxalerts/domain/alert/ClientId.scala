package pe.quiroz.fxalerts.domain.alert

import pe.quiroz.fxalerts.domain.DomainError

/** Regla incumplida al construir un [[ClientId]]. */
enum ClientIdViolation:
  case Blank, TooLong

object ClientIdViolation:
  /**
   * Longitud máxima del identificador. Cubre con holgura un RUC (11 dígitos), un UUID (36) o un
   * código interno; acota el índice de la tabla y evita almacenar texto arbitrario.
   */
  val maxLength: Int = 64

/**
 * Identificador del cliente comercial que registra la alerta.
 *
 * Es un tipo opaco sobre `String` con constructor inteligente: la única forma de obtener un
 * `ClientId` es a través de [[ClientId.from]], de modo que todo valor en circulación cumple las
 * invariantes. El identificador lo asigna un sistema externo (todavía no existe entidad cliente),
 * por eso se modela como texto y no como UUID.
 */
opaque type ClientId = String

object ClientId:

  /**
   * Construye un identificador a partir de texto libre.
   *
   * Se recortan los espacios en los extremos para que `"cliente-1 "` y `"cliente-1"` se refieran al
   * mismo cliente.
   */
  def from(raw: String): Either[DomainError.InvalidClientId, ClientId] =
    val trimmed = raw.trim
    if trimmed.isEmpty then Left(DomainError.InvalidClientId(raw, ClientIdViolation.Blank))
    else if trimmed.length > ClientIdViolation.maxLength then
      Left(DomainError.InvalidClientId(raw, ClientIdViolation.TooLong))
    else Right(trimmed)

  extension (id: ClientId) def value: String = id
