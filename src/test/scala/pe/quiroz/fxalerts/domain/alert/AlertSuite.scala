package pe.quiroz.fxalerts.domain.alert

import munit.FunSuite
import pe.quiroz.fxalerts.domain.DomainError

import java.time.Instant
import java.util.UUID

class AlertSuite extends FunSuite:

  private val id        = AlertId(UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e"))
  private val createdAt = Instant.parse("2026-08-28T15:00:00Z")
  private val clientId  = ClientId.from("cliente-001").toOption.get

  private def create(
      clientId: ClientId = clientId,
      threshold: BigDecimal = BigDecimal("3.80"),
      direction: CrossingDirection = CrossingDirection.Above
  ): Either[DomainError, Alert] =
    Alert.create(id, clientId, BcrpSeries.UsdPenSbsSell, threshold, direction, createdAt)

  private def valid(alert: Either[DomainError, Alert]): Alert =
    alert.fold(error => fail(s"Se esperaba una alerta válida: ${error.message}"), identity)

  // --- Casos válidos ---------------------------------------------------------------------------

  test("crea una alerta activa con updatedAt igual a createdAt"):
    val alert = valid(create())
    assertEquals(alert.id, id)
    assertEquals(alert.clientId.value, "cliente-001")
    assertEquals(alert.series, BcrpSeries.UsdPenSbsSell)
    assertEquals(alert.threshold.value, BigDecimal("3.8"))
    assertEquals(alert.direction, CrossingDirection.Above)
    assertEquals(alert.status, AlertStatus.Active)
    assertEquals(alert.createdAt, createdAt)
    assertEquals(alert.updatedAt, createdAt)

  test("acepta ambas direcciones de cruce"):
    assertEquals(
      valid(create(direction = CrossingDirection.Below)).direction,
      CrossingDirection.Below
    )

  test("normaliza el umbral eliminando ceros a la derecha"):
    assertEquals(valid(create(threshold = BigDecimal("3.80000"))).threshold.value.toString, "3.8")
    assertEquals(valid(create(threshold = BigDecimal("100.00"))).threshold.value.toString, "100")

  test("acepta un umbral con exactamente cuatro decimales"):
    assertEquals(
      valid(create(threshold = BigDecimal("3.7565"))).threshold.value,
      BigDecimal("3.7565")
    )

  test("acepta el umbral máximo representable"):
    assertEquals(
      valid(create(threshold = ThresholdViolation.maxValue)).threshold.value,
      ThresholdViolation.maxValue
    )

  // --- Umbral inválido -------------------------------------------------------------------------

  test("rechaza un umbral igual a cero"):
    assertEquals(
      create(threshold = BigDecimal(0)),
      Left(DomainError.InvalidThreshold(BigDecimal(0), ThresholdViolation.NotPositive))
    )

  test("rechaza un umbral negativo"):
    assertEquals(
      create(threshold = BigDecimal("-3.80")),
      Left(DomainError.InvalidThreshold(BigDecimal("-3.80"), ThresholdViolation.NotPositive))
    )

  test("rechaza un umbral con más de cuatro decimales significativos"):
    assertEquals(
      create(threshold = BigDecimal("3.80005")),
      Left(DomainError.InvalidThreshold(BigDecimal("3.80005"), ThresholdViolation.TooManyDecimals))
    )

  test("rechaza un umbral por encima del máximo representable"):
    assertEquals(
      create(threshold = BigDecimal("1000000")),
      Left(DomainError.InvalidThreshold(BigDecimal("1000000"), ThresholdViolation.TooLarge))
    )

  // --- Identificador de cliente ----------------------------------------------------------------
  //
  // Una alerta solo puede construirse con un ClientId ya válido; las reglas del identificador se
  // prueban sobre su constructor inteligente.

  test("ClientId recorta espacios en los extremos"):
    assertEquals(ClientId.from("  cliente-001  ").map(_.value), Right("cliente-001"))

  test("ClientId acepta la longitud máxima"):
    val raw = "c" * ClientIdViolation.maxLength
    assertEquals(ClientId.from(raw).map(_.value), Right(raw))

  test("ClientId rechaza un identificador vacío o compuesto solo por espacios"):
    assertEquals(ClientId.from(""), Left(DomainError.InvalidClientId("", ClientIdViolation.Blank)))
    assertEquals(
      ClientId.from("   \t "),
      Left(DomainError.InvalidClientId("   \t ", ClientIdViolation.Blank))
    )

  test("ClientId rechaza un identificador demasiado largo"):
    val raw = "c" * (ClientIdViolation.maxLength + 1)
    assertEquals(
      ClientId.from(raw),
      Left(DomainError.InvalidClientId(raw, ClientIdViolation.TooLong))
    )

  // --- Actualización ---------------------------------------------------------------------------

  test("update reemplaza la configuración y conserva identidad, cliente y createdAt"):
    val original  = valid(create())
    val updatedAt = createdAt.plusSeconds(3600)
    val updated   = valid(
      original.update(
        series = BcrpSeries.UsdPenSbsSell,
        threshold = BigDecimal("3.95"),
        direction = CrossingDirection.Below,
        status = AlertStatus.Inactive,
        updatedAt = updatedAt
      )
    )
    assertEquals(updated.id, original.id)
    assertEquals(updated.clientId, original.clientId)
    assertEquals(updated.createdAt, original.createdAt)
    assertEquals(updated.threshold.value, BigDecimal("3.95"))
    assertEquals(updated.direction, CrossingDirection.Below)
    assertEquals(updated.status, AlertStatus.Inactive)
    assertEquals(updated.updatedAt, updatedAt)

  test("update aplica las mismas reglas de umbral que la creación"):
    val original = valid(create())
    assertEquals(
      original.update(
        series = BcrpSeries.UsdPenSbsSell,
        threshold = BigDecimal(0),
        direction = CrossingDirection.Above,
        status = AlertStatus.Active,
        updatedAt = createdAt
      ),
      Left(DomainError.InvalidThreshold(BigDecimal(0), ThresholdViolation.NotPositive))
    )

  // --- Catálogo de series ----------------------------------------------------------------------

  test("BcrpSeries resuelve el código oficial y rechaza códigos desconocidos"):
    assertEquals(BcrpSeries.fromCode("PD04640PD"), Some(BcrpSeries.UsdPenSbsSell))
    assertEquals(BcrpSeries.fromCode("XX00000XX"), None)
