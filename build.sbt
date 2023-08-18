
name := "exist-algolia-index"

organization := "org.humanistika.exist.index.algolia"

version := "1.0"

licenses := Seq("GNU General Public License, version 3" -> url("http://opensource.org/licenses/gpl-3.0"))

homepage := Some(url("https://github.com/bcdh/exist-algolia-index"))


scalaVersion := "2.12.6"


import de.heikoseeberger.sbtheader.license.GPLv3

headers := Map(
  "scala" -> GPLv3("2017", "Belgrade Center for Digital Humanities")
)


libraryDependencies ++= {

  val scalazV = "7.2.26"
  val catsV = "3.5.1"
  val existV = "4.4.0"
  val algoliaV = "2.19.0"
  val akkaV = "2.5.16"
  val jacksonV = "2.9.7"

  Seq(
    "org.scala-lang.modules" %% "scala-parser-combinators" % "1.1.1",
    "org.scala-lang.modules" %% "scala-java8-compat" % "0.9.0",
    "org.scalaz" %% "scalaz-core" % scalazV,
    "org.typelevel" %% "cats-effect" % catsV,

    "org.clapper" %% "grizzled-slf4j" % "1.3.2"
      exclude("org.slf4j", "slf4j-api"),

    "org.exist-db" % "exist-core" % existV % Provided
      exclude("org.exist-db.thirdparty.javax.xml.xquery", "xqjapi"),
    "net.sf.saxon" % "Saxon-HE" % "9.6.0-7" % Provided,
    "com.fasterxml.jackson.core" % "jackson-core" % jacksonV % Provided,
    "commons-codec" %	"commons-codec"	% "1.11" % Provided,

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
    "com.typesafe.akka" %% "akka-testkit" % akkaV,

    "org.specs2" %% "specs2-core" % "3.9.5" % Test,
    "org.easymock" % "easymock" % "3.6" % Test,

    "org.exist-db" % "exist-start" % existV % Test,
    "org.apache.httpcomponents" % "httpclient" % "4.5.6" % Test
  )
}

scalacOptions in Test ++= Seq("-Yrangepos")

resolvers +=
  Resolver.mavenLocal

resolvers +=
  "eXist Maven Repo" at "https://raw.github.com/eXist-db/mvn-repo/master/"


// Fancy up the Assembly JAR
packageOptions in (Compile, packageBin) +=  {
  import java.text.SimpleDateFormat
  import java.util.Calendar
  import java.util.jar.Manifest

  val gitCommit = "git rev-parse HEAD".!!.trim
  val gitTag = "git name-rev --tags --name-only $(git rev-parse HEAD)".!!.trim

  val additional = Map(
    "Build-Timestamp" -> new SimpleDateFormat("yyyyMMddHHmmss").format(Calendar.getInstance.getTime),
    "Built-By" -> sys.props("user.name"),
    "Build-Tag" -> gitTag,
    "Source-Repository" -> "scm:git:bcdh/exist-algolia-index.git",
    "Git-Commit-Abbrev" -> gitCommit.substring(0, 7),
    "Git-Commit" -> gitCommit,
    "Build-Jdk" -> sys.props("java.runtime.version"),
    "Description" -> "An eXist-db Index Plugin for Indexing with Algolia",
    "Build-Version" -> "N/A",
    "License" -> "GNU General Public License, version 3"
  )

  val manifest = new Manifest
  val attributes = manifest.getMainAttributes
  for((k, v) <- additional)
    attributes.putValue(k, v)
  Package.JarManifest(manifest)
}


// Add assembly to publish step
artifact in (Compile, assembly) := {
  val art = (artifact in (Compile, assembly)).value
  art.copy(`classifier` = Some("assembly"))
}

addArtifact(artifact in (Compile, assembly), assembly)


// Publish to Maven Central

publishMavenStyle := true

credentials += Credentials(Path.userHome / ".ivy2" / ".credentials")

publishTo := {
  val nexus = "https://oss.sonatype.org/"
  if (isSnapshot.value)
    Some("snapshots" at nexus + "content/repositories/snapshots")
  else
    Some("releases"  at nexus + "service/local/staging/deploy/maven2")
}

publishArtifact in Test := false

pomExtra := (
  <developers>
    <developer>
      <id>adamretter</id>
      <name>Adam Retter</name>
      <url>http://www.adamretter.org.uk</url>
    </developer>
  </developers>
  <scm>
    <url>git@github.com:bcdh/exist-algolia-index.git</url>
    <connection>scm:git:git@github.com:bcdh/exist-algolia-index.git</connection>
  </scm>)
