ThisBuild / scalaVersion := "3.3.8"
ThisBuild / organization := "pe.quiroz"
ThisBuild / version      := "0.1.0-SNAPSHOT"

lazy val root = (project in file("."))
  .settings(
    name := "bcrp-fx-alerts-service",
    description := "Servicio de alertas de tipo de cambio basado en la API de estadísticas del BCRP",
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Wunused:all",
      "-Wvalue-discard",
      "-Wnonunit-statement"
    ),
    libraryDependencies ++= Dependencies.all,
    Compile / run / fork         := true,
    Compile / run / connectInput := true,
    Test / fork                  := true,
    testFrameworks += new TestFramework("munit.Framework")
  )
