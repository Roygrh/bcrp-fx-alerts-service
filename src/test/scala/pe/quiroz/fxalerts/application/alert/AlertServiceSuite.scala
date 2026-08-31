package pe.quiroz.fxalerts.application.alert

import cats.effect.IO
import cats.effect.testkit.TestControl
import munit.CatsEffectSuite
import pe.quiroz.fxalerts.domain.DomainError
import pe.quiroz.fxalerts.domain.alert.*

import java.util.UUID

import scala.concurrent.duration.*

class AlertServiceSuite extends CatsEffectSuite:

  private val owner = ClientId.from("cliente-001").toOption.get
  private val other = ClientId.from("cliente-002").toOption.get

  private val createCommand = CreateAlert(
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

  test("create persiste una alerta activa a nombre del propietario y la devuelve"):
    withService { (service, repository) =>
      for
        alert  <- service.create(owner, createCommand).flatMap(rightOrFail)
        stored <- repository.findById(owner, alert.id)
      yield
        assertEquals(alert.clientId, owner)
        assertEquals(alert.threshold.value, BigDecimal("3.8"))
        assertEquals(alert.status, AlertStatus.Active)
        assertEquals(alert.updatedAt, alert.createdAt)
        assertEquals(stored, Some(alert))
    }

  test("create asigna identificadores distintos a cada alerta"):
    withService { (service, _) =>
      for
        first  <- service.create(owner, createCommand).flatMap(rightOrFail)
        second <- service.create(owner, createCommand).flatMap(rightOrFail)
      yield assertNotEquals(first.id, second.id)
    }

  test("create devuelve el error de dominio y no persiste nada si el umbral es inválido"):
    withService { (service, repository) =>
      for
        result <- service.create(owner, createCommand.copy(threshold = BigDecimal(0)))
        all    <- repository.all
      yield
        assertEquals(
          result,
          Left(DomainError.InvalidThreshold(BigDecimal(0), ThresholdViolation.NotPositive))
        )
        assertEquals(all, Nil)
    }

  // --- get / list ------------------------------------------------------------------------------

  test("get devuelve la alerta existente de su propietario"):
    withService { (service, _) =>
      for
        created <- service.create(owner, createCommand).flatMap(rightOrFail)
        found   <- service.get(owner, created.id)
      yield assertEquals(found, Right(created))
    }

  test("get devuelve AlertNotFound para un identificador desconocido"):
    withService { (service, _) =>
      service
        .get(owner, unknownId)
        .map(result => assertEquals(result, Left(DomainError.AlertNotFound(unknownId))))
    }

  test("list devuelve solo las alertas del propietario, en orden de creación"):
    // El listado ordena por (createdAt, id) y el identificador es aleatorio: entre alertas que
    // comparten createdAt el desempate no preserva el orden de creación. Con el reloj real las
    // creaciones consecutivas pueden caer en el mismo instante, así que la prueba corre bajo
    // TestControl: cada pausa virtual garantiza marcas de tiempo distintas, que es la premisa de
    // "orden de creación", sin dormir de verdad.
    TestControl.executeEmbed {
      withService { (service, _) =>
        for
          first  <- service.create(owner, createCommand).flatMap(rightOrFail)
          _      <- IO.sleep(1.milli)
          _      <- service.create(other, createCommand).flatMap(rightOrFail)
          _      <- IO.sleep(1.milli)
          second <- service.create(owner, createCommand).flatMap(rightOrFail)
          mine   <- service.list(owner)
          none   <- service.list(ClientId.from("cliente-999").toOption.get)
        yield
          assert(first.createdAt.isBefore(second.createdAt))
          assertEquals(mine.map(_.id), List(first.id, second.id))
          assertEquals(none, Nil)
      }
    }

  // --- update ----------------------------------------------------------------------------------

  test("update reemplaza la configuración y persiste el resultado"):
    withService { (service, repository) =>
      for
        created <- service.create(owner, createCommand).flatMap(rightOrFail)
        updated <- service.update(owner, created.id, updateCommand).flatMap(rightOrFail)
        stored  <- repository.findById(owner, created.id)
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
        .update(owner, unknownId, updateCommand)
        .map(result => assertEquals(result, Left(DomainError.AlertNotFound(unknownId))))
    }

  test("update devuelve el error de dominio y no modifica la alerta si el umbral es inválido"):
    withService { (service, repository) =>
      for
        created <- service.create(owner, createCommand).flatMap(rightOrFail)
        result  <- service.update(
          owner,
          created.id,
          updateCommand.copy(threshold = BigDecimal("1.23456"))
        )
        stored <- repository.findById(owner, created.id)
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
        created <- service.create(owner, createCommand).flatMap(rightOrFail)
        result  <- service.delete(owner, created.id)
        stored  <- repository.findById(owner, created.id)
      yield
        assertEquals(result, Right(()))
        assertEquals(stored, None)
    }

  test("delete devuelve AlertNotFound para un identificador desconocido"):
    withService { (service, _) =>
      service
        .delete(owner, unknownId)
        .map(result => assertEquals(result, Left(DomainError.AlertNotFound(unknownId))))
    }

  // --- Aislamiento por cliente -----------------------------------------------------------------

  test("otro cliente no puede leer, modificar ni eliminar una alerta ajena: AlertNotFound"):
    withService { (service, repository) =>
      for
        created <- service.create(owner, createCommand).flatMap(rightOrFail)
        get     <- service.get(other, created.id)
        update  <- service.update(other, created.id, updateCommand)
        delete  <- service.delete(other, created.id)
        stored  <- repository.findById(owner, created.id)
      yield
        assertEquals(get, Left(DomainError.AlertNotFound(created.id)))
        assertEquals(update, Left(DomainError.AlertNotFound(created.id)))
        assertEquals(delete, Left(DomainError.AlertNotFound(created.id)))
        assertEquals(stored, Some(created))
    }
