name := "ChainSwap"

version := "0.1"

scalaVersion := "2.13.3"

libraryDependencies += "org.scalatest" % "scalatest_2.13" % "3.1.1"

libraryDependencies += "com.typesafe.akka" % "akka-testkit_2.13" % "2.6.10"

libraryDependencies += "com.softwaremill.quicklens" % "quicklens_2.13" % "1.6.1"

libraryDependencies += "com.iheart" % "ficus_2.13" % "1.5.0"

libraryDependencies += "com.typesafe.slick" %% "slick" % "3.3.3"

libraryDependencies += "com.typesafe.slick" %% "slick-hikaricp" % "3.3.3"

libraryDependencies += "org.postgresql" % "postgresql" % "42.2.18"

libraryDependencies += "org.scala-lang.modules" %% "scala-parallel-collections" % "0.2.0"

enablePlugins(ReproducibleBuildsPlugin)