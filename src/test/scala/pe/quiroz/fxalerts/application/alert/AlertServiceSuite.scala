package pe.quiroz.fxalerts.application.alert

import cats.effect.IO
import munit.CatsEffectSuite
import pe.quiroz.fxalerts.domain.DomainError
import pe.quiroz.fxalerts.domain.alert.*

import java.util.UUID

class AlertServiceSuite extends CatsEffectSuite:

  private val createCommand = CreateAlert(
    clientId = "cliente-001",
    series = BcrpSeries.UsdPenSbsSell,
    threshold = BigDecimal("3.80"),
    direction = CrossingDirection.Above
  )

  private val updateCommand = UpdateAlert(
    series = BcrpSeries.UsdPenSbsSell,
    threshold = BigDecimal("3.95"),
    direction = CrossingDirection.Below,
    status = AlertStatus.Inactive
  )

  private val unknownId = AlertId(UUID.fromString("00000000-0000-0000-0000-000000000000"))

  private def withService[A](body: (AlertService[IO], InMemoryAlertRepository) => IO[A]): IO[A] =
    InMemoryAlertRepository.empty.flatMap(repository =>
      body(AlertService[IO](repository), repository)
    )

  private def rightOrFail[A](result: Either[DomainError, A]): IO[A] =
    IO.fromEither(result.left.map(error => new AssertionError(error.message)))

  // --- create ----------------------------------------------------------------------------------

  test("create persiste una alerta activa y la devuelve"):
    withService { (service, repository) =>
      for
        alert  <- service.create(createCommand).flatMap(rightOrFail)
        stored <- repository.findById(alert.id)
      yield
        assertEquals(alert.clientId.value, "cliente-001")
        assertEquals(alert.threshold.value, BigDecimal("3.8"))
        assertEquals(alert.status, AlertStatus.Active)
        assertEquals(alert.updatedAt, alert.createdAt)
        assertEquals(stored, Some(alert))
    }

  test("create asigna identificadores distintos a cada alerta"):
    withService { (service, _) =>
      for
        first  <- service.create(createCommand).flatMap(rightOrFail)
        second <- service.create(createCommand).flatMap(rightOrFail)
      yield assertNotEquals(first.id, second.id)
    }

  test("create devuelve el error de dominio y no persiste nada si el umbral es inválido"):
    withService { (service, repository) =>
      for
        result <- service.create(createCommand.copy(threshold = BigDecimal(0)))
        all    <- repository.findAll(None)
      yield
        assertEquals(
          result,
          Left(DomainError.InvalidThreshold(BigDecimal(0), ThresholdViolation.NotPositive))
        )
        assertEquals(all, Nil)
    }

  test("create devuelve el error de dominio si el cliente es inválido"):
    withService { (service, _) =>
      service.create(createCommand.copy(clientId = "  ")).map { result =>
        assertEquals(result, Left(DomainError.InvalidClientId("  ", ClientIdViolation.Blank)))
      }
    }

  // --- get / list ------------------------------------------------------------------------------

  test("get devuelve la alerta existente"):
    withService { (service, _) =>
      for
        created <- service.create(createCommand).flatMap(rightOrFail)
        found   <- service.get(created.id)
      yield assertEquals(found, Right(created))
    }

  test("get devuelve AlertNotFound para un identificador desconocido"):
    withService { (service, _) =>
      service
        .get(unknownId)
        .map(result => assertEquals(result, Left(DomainError.AlertNotFound(unknownId))))
    }

  test("list devuelve todas las alertas o solo las del cliente indicado"):
    withService { (service, _) =>
      for
        first  <- service.create(createCommand).flatMap(rightOrFail)
        second <- service.create(createCommand.copy(clientId = "cliente-002")).flatMap(rightOrFail)
        clientId <- rightOrFail(ClientId.from("cliente-002"))
        all      <- service.list(None)
        filtered <- service.list(Some(clientId))
        none     <- service.list(ClientId.from("cliente-999").toOption)
      yield
        assertEquals(all.map(_.id).toSet, Set(first.id, second.id))
        assertEquals(filtered, List(second))
        assertEquals(none, Nil)
    }

  // --- update ----------------------------------------------------------------------------------

  test("update reemplaza la configuración y persiste el resultado"):
    withService { (service, repository) =>
      for
        created <- service.create(createCommand).flatMap(rightOrFail)
        updated <- service.update(created.id, updateCommand).flatMap(rightOrFail)
        stored  <- repository.findById(created.id)
      yield
        assertEquals(updated.id, created.id)
        assertEquals(updated.clientId, created.clientId)
        assertEquals(updated.createdAt, created.createdAt)
        assertEquals(updated.threshold.value, BigDecimal("3.95"))
        assertEquals(updated.direction, CrossingDirection.Below)
        assertEquals(updated.status, AlertStatus.Inactive)
        assert(!updated.updatedAt.isBefore(created.updatedAt))
        assertEquals(stored, Some(updated))
    }

  test("update devuelve AlertNotFound para un identificador desconocido"):
    withService { (service, _) =>
      service
        .update(unknownId, updateCommand)
        .map(result => assertEquals(result, Left(DomainError.AlertNotFound(unknownId))))
    }

  test("update devuelve el error de dominio y no modifica la alerta si el umbral es inválido"):
    withService { (service, repository) =>
      for
        created <- service.create(createCommand).flatMap(rightOrFail)
        result  <- service.update(created.id, updateCommand.copy(threshold = BigDecimal("1.23456")))
        stored  <- repository.findById(created.id)
      yield
        assertEquals(
          result,
          Left(
            DomainError.InvalidThreshold(BigDecimal("1.23456"), ThresholdViolation.TooManyDecimals)
          )
        )
        assertEquals(stored, Some(created))
    }

  // --- delete ----------------------------------------------------------------------------------

  test("delete elimina la alerta existente"):
    withService { (service, repository) =>
      for
        created <- service.create(createCommand).flatMap(rightOrFail)
        result  <- service.delete(created.id)
        stored  <- repository.findById(created.id)
      yield
        assertEquals(result, Right(()))
        assertEquals(stored, None)
    }

  test("delete devuelve AlertNotFound para un identificador desconocido"):
    withService { (service, _) =>
      service
        .delete(unknownId)
        .map(result => assertEquals(result, Left(DomainError.AlertNotFound(unknownId))))
    }
