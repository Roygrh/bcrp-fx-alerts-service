package pe.quiroz.fxalerts.application.security

/**
 * Alcances (scopes) de autorización de la API, según el vocabulario de OAuth 2.0 (RFC 6749 §3.3).
 *
 * Cada alcance autoriza una capacidad concreta y se concede por cliente en el registro de clientes.
 * Un token transporta el subconjunto de alcances solicitado (o todos los concedidos si no solicita
 * ninguno) y cada endpoint protegido exige exactamente uno. El alcance de escritura no implica el
 * de lectura: un cliente que solo registra alertas no necesita listarlas.
 *
 * @param value
 *   forma textual con la que viaja en las peticiones, los tokens y la documentación OpenAPI
 * @param description
 *   explicación mostrada en la documentación
 */
enum Scope(val value: String, val description: String):
  case AlertsRead  extends Scope("alerts:read", "Consultar las alertas propias")
  case AlertsWrite extends Scope("alerts:write", "Registrar, modificar y eliminar alertas propias")
  case RatesRead   extends Scope("rates:read", "Consultar el tipo de cambio vigente")

object Scope:

  def fromValue(raw: String): Option[Scope] = values.find(_.value == raw)

  /**
   * Interpreta una lista de alcances separados por espacios, tal como la define RFC 6749 §3.3.
   *
   * @return
   *   los alcances reconocidos, o los textos que no corresponden a ningún alcance conocido
   */
  def parseList(raw: String): Either[List[String], Set[Scope]] =
    val requested = raw.trim.split("\\s+").toList.filter(_.nonEmpty)
    val unknown   = requested.filter(fromValue(_).isEmpty)
    if unknown.nonEmpty then Left(unknown)
    else Right(requested.flatMap(fromValue).toSet)

  /** Forma textual de un conjunto de alcances, en orden de declaración y separados por espacio. */
  def render(scopes: Set[Scope]): String =
    values.filter(scopes.contains).map(_.value).mkString(" ")
