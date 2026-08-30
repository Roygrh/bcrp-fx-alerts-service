ThisBuild / scalaVersion := "3.3.8"
ThisBuild / organization := "pe.quiroz"
ThisBuild / version      := "0.1.0-SNAPSHOT"

lazy val commonSettings = Seq(
  scalacOptions ++= Seq(
    "-deprecation",
    "-feature",
    "-unchecked",
    "-Wunused:all",
    "-Wvalue-discard",
    "-Wnonunit-statement",
    // Los avisos son errores: en particular, un `match` no exhaustivo sobre una jerarquía
    // sellada (p. ej. DomainError -> respuestas HTTP) debe romper la compilación.
    "-Werror"
  ),
  Test / fork := true,
  testFrameworks += new TestFramework("munit.Framework")
)

lazy val root = (project in file("."))
  .settings(commonSettings)
  .settings(
    name := "bcrp-fx-alerts-service",
    description := "Servicio de alertas de tipo de cambio basado en la API de estadísticas del BCRP",
    libraryDependencies ++= Dependencies.all,
    Compile / run / fork         := true,
    Compile / run / connectInput := true
  )

// Pruebas de integración contra PostgreSQL real (Testcontainers). Viven en un subproyecto
// separado, no agregado por `root`, para que `sbt test` nunca dependa de Docker:
//   sbt test               -> unitarias (sin infraestructura)
//   sbt integration/test   -> integración (requiere Docker en ejecución)
lazy val integration = (project in file("integration"))
  .dependsOn(root % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    name           := "bcrp-fx-alerts-service-integration",
    publish / skip := true,
    libraryDependencies ++= Dependencies.integrationTests,
    Test / parallelExecution := false
  )
