package pe.quiroz.fxalerts.infrastructure.persistence

import cats.effect.MonadCancelThrow
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import pe.quiroz.fxalerts.application.health.DatabaseHealthCheck

/** Adaptador de [[DatabaseHealthCheck]] que ejecuta una consulta trivial a través de doobie. */
final class DoobieDatabaseHealthCheck[F[_]: MonadCancelThrow](transactor: Transactor[F])
    extends DatabaseHealthCheck[F]:

  def ping: F[Unit] =
    sql"SELECT 1".query[Int].unique.transact(transactor).void
