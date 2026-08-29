import sbt._

object Dependencies {

  object Versions {
    val catsEffect      = "3.7.1"
    val http4s          = "0.23.36"
    val tapir           = "1.13.31"
    val doobie          = "1.0.0-RC12"
    val circe           = "0.14.16"
    val ciris           = "3.15.0"
    val flyway          = "13.4.0"
    val postgresDriver  = "42.7.13"
    val log4cats        = "2.8.0"
    val logback         = "1.6.3"
    val munit           = "1.3.5"
    val munitCatsEffect = "2.2.0"
  }

  val catsEffect: ModuleID = "org.typelevel" %% "cats-effect" % Versions.catsEffect

  val http4s: Seq[ModuleID] = Seq(
    "org.http4s" %% "http4s-ember-server" % Versions.http4s,
    "org.http4s" %% "http4s-dsl"          % Versions.http4s
  )

  val tapir: Seq[ModuleID] = Seq(
    "com.softwaremill.sttp.tapir" %% "tapir-core"              % Versions.tapir,
    "com.softwaremill.sttp.tapir" %% "tapir-http4s-server"     % Versions.tapir,
    "com.softwaremill.sttp.tapir" %% "tapir-json-circe"        % Versions.tapir,
    "com.softwaremill.sttp.tapir" %% "tapir-swagger-ui-bundle" % Versions.tapir
  )

  val circe: Seq[ModuleID] = Seq(
    "io.circe" %% "circe-core"    % Versions.circe,
    "io.circe" %% "circe-generic" % Versions.circe,
    "io.circe" %% "circe-parser"  % Versions.circe
  )

  val doobie: Seq[ModuleID] = Seq(
    "org.tpolecat" %% "doobie-core"     % Versions.doobie,
    "org.tpolecat" %% "doobie-hikari"   % Versions.doobie,
    "org.tpolecat" %% "doobie-postgres" % Versions.doobie
  )

  val ciris: Seq[ModuleID] = Seq(
    "is.cir" %% "ciris"        % Versions.ciris,
    "is.cir" %% "ciris-http4s" % Versions.ciris
  )

  val flyway: Seq[ModuleID] = Seq(
    "org.flywaydb" % "flyway-core"                % Versions.flyway,
    "org.flywaydb" % "flyway-database-postgresql" % Versions.flyway % Runtime
  )

  val postgresDriver: ModuleID =
    "org.postgresql" % "postgresql" % Versions.postgresDriver % Runtime

  val logging: Seq[ModuleID] = Seq(
    "org.typelevel" %% "log4cats-slf4j"  % Versions.log4cats,
    "ch.qos.logback" % "logback-classic" % Versions.logback % Runtime
  )

  val tests: Seq[ModuleID] = Seq(
    "org.scalameta" %% "munit"             % Versions.munit,
    "org.typelevel" %% "munit-cats-effect" % Versions.munitCatsEffect
  ).map(_ % Test)

  val all: Seq[ModuleID] =
    Seq(catsEffect, postgresDriver) ++
      http4s ++ tapir ++ circe ++ doobie ++ ciris ++ flyway ++ logging ++ tests
}
