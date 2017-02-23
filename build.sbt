
name := "exist-algolia-index"

organization := "org.ttasovac.exist.index"

version := "1.0"

licenses := Seq(">GNU General Public License, version 3" -> url("http://opensource.org/licenses/gpl-3.0"))

homepage := Some(url("https://github.com/ttasovac/exist-algolia-index"))


scalaVersion := "2.12.0"


import de.heikoseeberger.sbtheader.license.GPLv3

headers := Map(
  "scala" -> GPLv3("2017", "Toma Tasovac")
)


libraryDependencies ++= {

  val scalazV = "7.2.7"
  val fs2V = "0.9.2"
  val existV = "201701082031-SNAPSHOT"
  val algoliaV = "2.8.0"
  val akkaV = "2.4.14"
  val jacksonV = "2.7.4"

  Seq(
    "org.scala-lang.modules" %% "scala-java8-compat" % "0.8.0",
    "org.scalaz" %% "scalaz-core" % scalazV,
    "com.jsuereth" %% "scala-arm" % "2.0",
    "co.fs2" %% "fs2-core" % fs2V,
    "co.fs2" %% "fs2-io" % fs2V,

    "org.clapper" %% "grizzled-slf4j" % "1.3.0"
      exclude("org.slf4j", "slf4j-api"),

    "org.exist-db" % "exist-core" % existV % "provided"
      exclude("org.exist-db.thirdparty.javax.xml.xquery", "xqjapi"),
    "net.sf.saxon" % "Saxon-HE" % "9.6.0-7" % "provided",
    "com.fasterxml.jackson.core" % "jackson-core" % jacksonV % "provided",
    "commons-codec" %	"commons-codec"	% "1.10" % "provided",

    "com.fasterxml.jackson.core" % "jackson-databind" % jacksonV
      exclude("com.fasterxml.jackson.core", "jackson-core"),
    "com.algolia" % "algoliasearch" % algoliaV
      exclude("org.apache.httpcomponents", "*"),
    "com.algolia" % "algoliasearch-common" % algoliaV
      exclude("com.fasterxml.jackson.core", "jackson-core")
      exclude("com.fasterxml.jackson.core", "jackson-databind")
      exclude("org.apache.commons" ,"commons-lang3")
      exclude("org.slf4j", "slf4j-api"),

    "com.typesafe.akka" %% "akka-actor" % akkaV,

    "org.specs2" %% "specs2-core" % "3.8.6" % "test"
  )
}

scalacOptions in Test ++= Seq("-Yrangepos")

resolvers +=
  Resolver.mavenLocal

resolvers +=
  "eXist Maven Repo" at "https://raw.github.com/eXist-db/mvn-repo/master/"


// Publish to Maven Central

publishMavenStyle := true

publishTo := {
  val nexus = "https://oss.sonatype.org/"
  if (isSnapshot.value)
    Some("snapshots" at nexus + "content/repositories/snapshots")
  else
    Some("releases"  at nexus + "service/local/staging/deploy/maven2")
}

publishArtifact in Test := false

pomExtra := (
    <scm>
      <url>git@github.com:ttasovac/exist-algolia-index.git</url>
      <connection>scm:git:git@github.com:ttasovac/exist-algolia-index.git</connection>
    </scm>
    <developers>
      <developer>
        <id>adamretter</id>
        <name>Adam Retter</name>
        <url>http://www.adamretter.org.uk</url>
      </developer>
    </developers>)