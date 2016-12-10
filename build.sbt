
name := "exist-algolia-index"

version := "1.0"

scalaVersion := "2.12.0"

libraryDependencies ++= {

  val scalazV = "7.2.7"
  val existV = "20161201-SNAPSHOT"
  val algoliaV = "2.5.0"

  Seq(
    "org.scalaz" %% "scalaz-core" % scalazV,
    "org.exist-db" % "exist-core" % existV % "provided" exclude("org.exist-db.thirdparty.javax.xml.xquery", "xqjapi"),
    "net.sf.saxon" % "Saxon-HE" % "9.6.0-7" % "provided",
    "com.algolia" % "algoliasearch-async" % algoliaV exclude("org.apache.httpcomponents", "*") exclude("com.fasterxml.jackson.core", "jackson-core"),
    "com.algolia" % "algoliasearch-common" % algoliaV,

    "org.specs2" %% "specs2-core" % "3.8.6" % "test"
  )
}

scalacOptions in Test ++= Seq("-Yrangepos")

resolvers +=
  Resolver.mavenLocal

resolvers +=
  "eXist Maven Repo" at "https://raw.github.com/eXist-db/mvn-repo/master/"
