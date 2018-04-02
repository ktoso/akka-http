/*
 * Copyright (C) 2016-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import scala.language.postfixOps
import sbt._, Keys._

/**
 * For projects that are not published.
 */
object NoPublish extends AutoPlugin {
  override def requires = plugins.JvmPlugin

  override def projectSettings = Seq(
    publishArtifact := false,
    publish := {},
    publishLocal := {}
  )

}

object Publish extends AutoPlugin {
//  import bintray.BintrayPlugin
//  import bintray.BintrayPlugin.autoImport._

  override def trigger = allRequirements
//  override def requires = BintrayPlugin

  override def projectSettings = Seq(
    publishMavenStyle := true,
    publishTo := {
      val nexus = "https://oss.sonatype.org/"
      if (isSnapshot.value)
        Some("snapshots" at nexus + "content/repositories/snapshots")
      else
        Some("releases"  at nexus + "service/local/staging/deploy/maven2")
    }

//    bintrayOrganization := Some("akka"),
//    bintrayPackage := "com.typesafe.akka:akka-http_2.11"
  ) ++ sonatypeSettings

  val sonatypeSettings: Seq[Setting[_]] = Seq(
    publishMavenStyle := true,
    publishArtifact in Test := false,
    pomIncludeRepository := { _ => false },
    publishTo := {
      val nexus = "https://oss.sonatype.org/"
      if (version.value.trim.endsWith("SNAPSHOT"))
        Some("snapshots" at nexus + "content/repositories/snapshots")
      else
        Some("releases" at nexus + "service/local/staging/deploy/maven2")
    },
    credentials += Credentials(Path.userHome / ".sbt" / "sonatype.properties"),
    pomExtra :=
      <url>https://github.com/akka/akka-http</url>
        <licenses>
          <license>
            <name>Apache License, Version 2.0</name>
            <url>http://www.apache.org/licenses/LICENSE-2.0</url>
            <distribution>repo</distribution>
          </license>
        </licenses>
        <scm>
          <url>git@github.com:akka/akka-http.git</url>
          <connection>scm:git:git@github.com:akka/akka-http.git</connection>
        </scm>
        <developers>
          <developer>
            <id>contributors</id>
            <name>Contributors</name>
            <email>akka-user@googlegroups.com</email>
            <url>https://github.com/akka/akka-http/graphs/contributors</url>
          </developer>
        </developers>
        <parent>
          <groupId>org.sonatype.oss</groupId>
          <artifactId>oss-parent</artifactId>
          <version>7</version>
        </parent>
  )

}

object DeployRsync extends AutoPlugin {
  import scala.sys.process._
  import sbt.complete.DefaultParsers._

  override def requires = plugins.JvmPlugin

  trait Keys {
    val deployRsyncArtifact = taskKey[Seq[(File, String)]]("File or directory and a path to deploy to")
    val deployRsync = inputKey[Unit]("Deploy using SCP")
  }

  object autoImport extends Keys
  import autoImport._

  override def projectSettings = Seq(
    deployRsync := {
      val (_, host) = (Space ~ StringBasic).parsed
      deployRsyncArtifact.value.foreach {
        case (from, to) => s"rsync -rvz $from/ $host:$to"!
      }
    }
  )
}
