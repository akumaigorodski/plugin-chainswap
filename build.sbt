name := "ChainSwap"

version := "0.1"

scalaVersion := "2.13.3"

libraryDependencies += "org.scalatest" % "scalatest_2.13" % "3.1.1"

libraryDependencies += "com.typesafe.akka" % "akka-actor_2.13" % "2.6.9"

libraryDependencies += "com.typesafe.akka" % "akka-testkit_2.13" % "2.6.9"

libraryDependencies += "com.iheart" % "ficus_2.13" % "1.5.0"

libraryDependencies += "com.typesafe.slick" %% "slick" % "3.3.2"

libraryDependencies += "com.typesafe.slick" %% "slick-hikaricp" % "3.3.2"

libraryDependencies += "org.postgresql" % "postgresql" % "9.4-1206-jdbc42"

libraryDependencies += "org.zeromq" % "jeromq" % "0.5.2"

libraryDependencies += "fr.acinq" % "bitcoin-lib_2.13" % "0.18"

libraryDependencies += "com.google.guava" % "guava" % "28.2-jre"

libraryDependencies += "org.scala-lang.modules" %% "scala-parallel-collections" % "0.2.0"