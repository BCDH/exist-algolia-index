import ReleaseTransformations._

ThisBuild / versionScheme := Some("semver-spec")

lazy val root = Project("exist-algolia-index", file("."))
  .settings(
    name := "exist-algolia-index",
    organization := "org.humanistika.exist.index.algolia",
    scalaVersion := "2.13.12",
    licenses := Seq("GNU General Public License, version 3" -> url("http://opensource.org/licenses/gpl-3.0")),
    homepage := Some(url("https://github.com/bcdh/exist-algolia-index")),
    startYear := Some(2016),
    description := "An eXist-db Index Plugin for Indexing with Algolia",
    organizationName := "Belgrade Center for Digital Humanities",
    organizationHomepage := Some(url("http://bcdh.org")),
    scmInfo := Some(ScmInfo(
      url("https://github.com/bcdh/exist-algolia-index"),
      "scm:git@github.com:bcdh/exist-algolia-index.git")
    ),
    developers := List(
      Developer(
        id = "adamretter",
        name = "Adam Retter",
        email = "adam@evolvedbinary.com",
        url = url("https://www.evolvedbinary.com")
      )
    ),
    headerLicense := Some(HeaderLicense.GPLv3("2016", "Belgrade Center for Digital Humanities")),
    xjcLibs := Seq(
          "org.glassfish.jaxb" % "jaxb-xjc"           % "2.3.8",
          "com.sun.xml.bind"   % "jaxb-impl"          % "2.3.8",
          "com.sun.activation" % "jakarta.activation" % "1.2.2"
    ),
    libraryDependencies ++= {

  val catsCoreV = "2.10.0"
  val existV = "6.3.0-SNAPSHOT"
  val algoliaV = "2.19.0"
  val akkaV = "2.6.20"
  val jacksonV = "2.13.4"

  Seq(
        "org.scala-lang.modules" %% "scala-java8-compat" % "1.0.2",
        "org.typelevel" %% "cats-core" % catsCoreV,

        "org.parboiled" %% "parboiled" % "2.5.0",

        "javax.xml.bind" % "jaxb-api" % "2.3.1",

        "org.clapper" %% "grizzled-slf4j" % "1.3.4"
          exclude("org.slf4j", "slf4j-api"),

        "org.exist-db" % "exist-core" % existV % Provided
          exclude("org.exist-db.thirdparty.javax.xml.xquery", "xqjapi")
          exclude("jakarta.xml.bind", "jakarta.xml.bind-api"),
        "net.sf.saxon" % "Saxon-HE" % "9.9.1-8" % Provided,
        "com.fasterxml.jackson.core" % "jackson-core" % jacksonV % Provided,
        "commons-codec" % "commons-codec" % "1.15" % Provided,

        "com.fasterxml.jackson.core" % "jackson-databind" % jacksonV
          exclude("com.fasterxml.jackson.core", "jackson-core"),
        "com.algolia" % "algoliasearch" % algoliaV
          exclude("org.apache.httpcomponents", "*"),
        "com.algolia" % "algoliasearch-common" % algoliaV
          exclude("com.fasterxml.jackson.core", "jackson-core")
          exclude("com.fasterxml.jackson.core", "jackson-databind")
          exclude("org.apache.commons", "commons-lang3")
          exclude("org.slf4j", "slf4j-api"),

        "com.typesafe.akka" %% "akka-actor" % akkaV,
        "com.typesafe.akka" %% "akka-testkit" % akkaV,

        "org.specs2" %% "specs2-core" % "4.20.2" % Test,
        "org.easymock" % "easymock" % "3.6" % Test,

        "org.exist-db" % "exist-start" % existV % Test,
        "org.apache.httpcomponents" % "httpclient" % "4.5.14" % Test
      )
    },
    publishMavenStyle := true,
    credentials += Credentials(Path.userHome / ".ivy2" / ".credentials"),
    publishTo := {
      val nexus = "https://oss.sonatype.org/"
      if (isSnapshot.value)
        Some("snapshots" at nexus + "content/repositories/snapshots/")
      else
        Some("releases" at nexus + "service/local/staging/deploy/maven2/")
    },
    Test / publishArtifact := false,
//    scalacOptions in Test ++= Seq("-Yrangepos")
    releaseVersionBump := sbtrelease.Version.Bump.Major,
    releasePublishArtifactsAction := PgpKeys.publishSigned.value,

    resolvers += Resolver.mavenLocal,
    resolvers += "eXist Maven Repo" at "https://raw.github.com/eXist-db/mvn-repo/master/"
  )

// Fancy up the Assembly JAR
Compile / packageBin / packageOptions += {
  import java.text.SimpleDateFormat
  import java.util.Calendar
  import java.util.jar.Manifest
  import scala.sys.process._

  val gitCommit = "git rev-parse HEAD".!!.trim
  val gitTag = s"git name-rev --tags --name-only $gitCommit".!!.trim

  val additional = Map(
    "Build-Timestamp" -> new SimpleDateFormat("yyyyMMddHHmmss").format(Calendar.getInstance.getTime),
    "Built-By" -> sys.props("user.name"),
    "Build-Tag" -> gitTag,
    "Source-Repository" -> "scm:git:https://github.com/bcdh/exist-algolia-index.git",
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

Compile / assembly / artifact := {
  val art = (Compile / assembly / artifact).value
  art.withClassifier(Some("assembly"))
}

assembly / assemblyMergeStrategy := {
  case PathList("META-INF", "versions", "9", xs @ _*) => MergeStrategy.discard
  case x if x.endsWith("module-info.class") => MergeStrategy.discard
  case x =>
    val oldStrategy = (assembly / assemblyMergeStrategy).value
    oldStrategy(x)
}

addArtifact(Compile / assembly / artifact, assembly)

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
