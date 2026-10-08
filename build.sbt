name := "trapezoid"
version := "1.0"
scalaVersion := "2.12.16"
val spinalVersion = "1.7.0"
val scalatestVersion = "3.2.7"
val breezeVersion = "2.0.1-RC1"

// For process configuration
//libraryDependencies += "org.json4s" %% "json4s-native" % "4.0.0"
libraryDependencies += "org.scala-lang.modules" %% "scala-parser-combinators" % "2.1.1"
libraryDependencies ++= Seq(
  "com.fasterxml.jackson.module" %% "jackson-module-scala" % "2.15.2",
  "com.fasterxml.jackson.core" % "jackson-databind" % "2.15.2",
  "com.fasterxml.jackson.dataformat" % "jackson-dataformat-yaml" % "2.15.2"
)


val spinal = Seq(
  // scalaTest
  "org.scalatest" %% "scalatest" % scalatestVersion ,
  "org.scalatest" %% "scalatest" % scalatestVersion % "test",
  // breeze
  "org.scalanlp" %% "breeze" % breezeVersion,
  // os-lib
  "com.lihaoyi" %% "os-lib" % "0.8.0",
  // spinalHdl
  "com.github.spinalhdl" %% "spinalhdl-core" % spinalVersion,
  "com.github.spinalhdl" %% "spinalhdl-lib" % spinalVersion,
  "com.github.spinalhdl" %% "spinalhdl-sim" % spinalVersion % Test,
  compilerPlugin("com.github.spinalhdl" %% "spinalhdl-idsl-plugin" % spinalVersion)
)

//lazy val spinalTblib = ProjectRef(file("../SpinalHDL-TB"), "SpinalHDL_Extend")
lazy val spinalTbLib = (project in file ("spinalhdl-tb")).settings(
  name:= "SpinalHDL_Extend",
  libraryDependencies ++= spinal
)

lazy val root = (project in file(".")).settings(
  libraryDependencies ++= spinal ,
  javaOptions := Seq("-Xmx16G")
).dependsOn(spinalTbLib)

fork := true
