package pe.quiroz.fxalerts.infrastructure.persistence

import cats.effect.IO
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.postgres.sqlstate.class23.{CHECK_VIOLATION, UNIQUE_VIOLATION}
import munit.CatsEffectSuite
import pe.quiroz.fxalerts.domain.DomainError
import pe.quiroz.fxalerts.domain.alert.*

import java.sql.SQLException
import java.time.Instant
import java.util.UUID

/** Pruebas de integración de [[DoobieAlertRepository]] contra PostgreSQL real. */
class DoobieAlertRepositorySuite extends CatsEffectSuite:

  private val database = ResourceSuiteLocalFixture("postgres", PostgresTestDatabase.transactor)

  override def munitFixtures = List(database)

  private val createdAt = Instant.parse("2026-08-28T15:00:00Z")

  private def alert(
      clientId: String,
      threshold: String = "3.80",
      createdAt: Instant = createdAt
  ): Alert =
    Alert
      .create(
        id = AlertId(UUID.randomUUID()),
        clientId = clientId,
        series = BcrpSeries.UsdPenSbsSell,
        threshold = BigDecimal(threshold),
        direction = CrossingDirection.Above,
        createdAt = createdAt
      )
      .fold(error => fail(s"Alerta de prueba inválida: ${error.message}"), identity)

  /** Registra una prueba que parte de una tabla vacía. */
  private def dbTest(
      name: String
  )(body: (DoobieAlertRepository[IO], Transactor[IO]) => IO[Unit]): Unit =
    test(name) {
      val transactor = database()
      sql"TRUNCATE TABLE alerts".update.run.transact(transactor) *>
        body(DoobieAlertRepository[IO](transactor), transactor)
    }

  /** Inserción directa en SQL, sin pasar por el dominio, para provocar las restricciones. */
  private def rawInsert(
      transactor: Transactor[IO],
      id: UUID = UUID.randomUUID(),
      clientId: String = "cliente-001",
      seriesCode: String = "PD04640PD",
      threshold: BigDecimal = BigDecimal("3.80"),
      direction: String = "ABOVE",
      status: String = "ACTIVE"
  ): IO[Either[Throwable, Int]] =
    sql"""
      INSERT INTO alerts (id, client_id, series_code, threshold, direction, status, created_at, updated_at)
      VALUES ($id, $clientId, $seriesCode, $threshold, $direction, $status, $createdAt, $createdAt)
    """.update.run.transact(transactor).attempt

  private def assertViolation(
      result: Either[Throwable, Int],
      sqlState: String,
      constraint: String
  ): Unit =
    result match
      case Left(error: SQLException) =>
        assertEquals(error.getSQLState, sqlState)
        assert(error.getMessage.contains(constraint), s"Mensaje inesperado: ${error.getMessage}")
      case other =>
        fail(s"Se esperaba una violación de $constraint, se obtuvo: $other")

  // --- Ciclo completo --------------------------------------------------------------------------

  dbTest("create y findById devuelven la misma alerta, umbral y marcas de tiempo incluidos"):
    (repository, _) =>
      val expected = alert("cliente-001", threshold = "3.7565")
      for
        _     <- repository.create(expected)
        found <- repository.findById(expected.id)
      yield assertEquals(found, Some(expected))

  dbTest("findById devuelve None para un identificador desconocido"): (repository, _) =>
    repository.findById(AlertId(UUID.randomUUID())).map(found => assertEquals(found, None))

  dbTest("findAll lista por fecha de creación y filtra por cliente"): (repository, _) =>
    val older  = alert("cliente-001", createdAt = createdAt)
    val newer  = alert("cliente-001", createdAt = createdAt.plusSeconds(60))
    val other  = alert("cliente-002", createdAt = createdAt.plusSeconds(30))
    val client = ClientId.from("cliente-001").toOption
    for
      _        <- List(newer, other, older).traverse_(repository.create)
      all      <- repository.findAll(None)
      filtered <- repository.findAll(client)
      none     <- repository.findAll(ClientId.from("cliente-999").toOption)
    yield
      assertEquals(all, List(older, other, newer))
      assertEquals(filtered, List(older, newer))
      assertEquals(none, Nil)

  dbTest("update persiste la nueva configuración de una alerta existente"): (repository, _) =>
    val original = alert("cliente-001")
    val updated  = original
      .update(
        series = BcrpSeries.UsdPenSbsSell,
        threshold = BigDecimal("3.95"),
        direction = CrossingDirection.Below,
        status = AlertStatus.Inactive,
        updatedAt = createdAt.plusSeconds(3600)
      )
      .fold(error => fail(error.message), identity)
    for
      _      <- repository.create(original)
      result <- repository.update(updated)
      found  <- repository.findById(original.id)
    yield
      assertEquals(result, Right(()))
      assertEquals(found, Some(updated))

  dbTest("update devuelve AlertNotFound si la alerta no existe"): (repository, _) =>
    val missing = alert("cliente-001")
    repository
      .update(missing)
      .map(result => assertEquals(result, Left(DomainError.AlertNotFound(missing.id))))

  dbTest("delete elimina la alerta y una segunda eliminación devuelve AlertNotFound"):
    (repository, _) =>
      val existing = alert("cliente-001")
      for
        _      <- repository.create(existing)
        first  <- repository.delete(existing.id)
        found  <- repository.findById(existing.id)
        second <- repository.delete(existing.id)
      yield
        assertEquals(first, Right(()))
        assertEquals(found, None)
        assertEquals(second, Left(DomainError.AlertNotFound(existing.id)))

  // --- Restricciones de la base de datos -------------------------------------------------------

  dbTest("la base de datos rechaza un identificador duplicado"): (repository, transactor) =>
    val existing = alert("cliente-001")
    for
      _      <- repository.create(existing)
      result <- rawInsert(transactor, id = existing.id.value)
    yield assertViolation(result, UNIQUE_VIOLATION.value, "alerts_pkey")

  dbTest("la base de datos rechaza un umbral no positivo"): (_, transactor) =>
    for
      zero     <- rawInsert(transactor, threshold = BigDecimal(0))
      negative <- rawInsert(transactor, threshold = BigDecimal("-1"))
    yield
      assertViolation(zero, CHECK_VIOLATION.value, "alerts_threshold_positive")
      assertViolation(negative, CHECK_VIOLATION.value, "alerts_threshold_positive")

  dbTest("la base de datos rechaza un cliente vacío o demasiado largo"): (_, transactor) =>
    for
      blank   <- rawInsert(transactor, clientId = "   ")
      tooLong <- rawInsert(transactor, clientId = "c" * 65)
    yield
      assertViolation(blank, CHECK_VIOLATION.value, "alerts_client_id_not_blank")
      assertViolation(tooLong, CHECK_VIOLATION.value, "alerts_client_id_length")

  dbTest("la base de datos rechaza códigos de serie, direcciones y estados fuera de catálogo"):
    (_, transactor) =>
      for
        series    <- rawInsert(transactor, seriesCode = "dolar")
        direction <- rawInsert(transactor, direction = "SIDEWAYS")
        status    <- rawInsert(transactor, status = "PAUSED")
      yield
        assertViolation(series, CHECK_VIOLATION.value, "alerts_series_code_format")
        assertViolation(direction, CHECK_VIOLATION.value, "alerts_direction_valid")
        assertViolation(status, CHECK_VIOLATION.value, "alerts_status_valid")
