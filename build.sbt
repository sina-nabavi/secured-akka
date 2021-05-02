name := "akka-quickstart-scala"

version := "1.0"

scalaVersion := "2.12.6"

lazy val akkaVersion = "2.5.25"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % akkaVersion,
  "org.scalatest" %% "scalatest" % "3.2.5",
  "me.vican.jorge" %% "dijon" % "0.4.0"
)

libraryDependencies += "com.typesafe.akka" %% "akka-testkit" % akkaVersion % Test