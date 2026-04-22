package org.humanistika.exist.index.algolia

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import akka.actor.Props
import org.exist.util.FileUtils
import org.humanistika.exist.index.algolia.backend.IndexLocalStoreActor
import org.humanistika.exist.index.algolia.backend.IndexLocalStoreManagerActor.collectionPathMatchesTree
import org.humanistika.exist.index.algolia.backend.IncrementalIndexingManagerActor.RemoveForCollection
import org.specs2.mutable.Specification

import scala.concurrent.duration._

class IndexLocalStoreManagerActorSpec extends Specification {

  "collectionPathMatchesTree" should {
    "match the exact collection path" in {
      collectionPathMatchesTree("/db/apps/raskovnik-data/data/MBRT.RDG", "/db/apps/raskovnik-data/data/MBRT.RDG") must beTrue
    }

    "match child collections under the requested collection path" in {
      collectionPathMatchesTree("/db/apps/raskovnik-data/data/MBRT.RDG", "/db/apps/raskovnik-data/data/MBRT.RDG/sub") must beTrue
    }

    "not match sibling collections that share only a prefix" in {
      collectionPathMatchesTree("/db/apps/raskovnik-data/data/MBRT.RDG", "/db/apps/raskovnik-data/data/MBRT.RDG2") must beFalse
    }
  }

  "IndexLocalStoreActor" should {
    "remove only matching collection-tree records for a child dictionary collection" in new AkkaTestkitSpecs2Support {
      val indexesDir = Files.createTempDirectory("algolia-index-local-store-index")
      try {
        val indexName = "ras"
        val targetCollection = "/db/apps/raskovnik-data/data/MBRT.RDG"
        val targetDocFile = writeStoredRootObject(indexesDir, indexName, "doc-target", 100L, "target.json", targetCollection)
        val childDocFile = writeStoredRootObject(indexesDir, indexName, "doc-child", 100L, "child.json", s"$targetCollection/sub")
        val siblingDocFile = writeStoredRootObject(indexesDir, indexName, "doc-sibling", 100L, "sibling.json", "/db/apps/raskovnik-data/data/MBRT.RDG2")

        val actor = system.actorOf(Props(classOf[IndexLocalStoreActor], indexesDir, indexName))
        actor ! RemoveForCollection(indexName, targetCollection)

        awaitCond(!Files.exists(targetDocFile), max = 1.second)
        awaitCond(!Files.exists(childDocFile), max = 1.second)
        Files.exists(siblingDocFile) must beTrue
      } finally {
        FileUtils.deleteQuietly(indexesDir)
      }
    }
  }

  private def writeStoredRootObject(indexesDir: Path, indexName: String, documentDir: String, timestamp: Long, filename: String, collectionPath: String): Path = {
    val timestampDir = indexesDir.resolve(indexName).resolve(documentDir).resolve(timestamp.toString)
    Files.createDirectories(timestampDir)

    val file = timestampDir.resolve(filename)
    val json =
      s"""{"objectID":"$filename","collection":"$collectionPath","documentID":"$documentDir"}"""
    Files.write(file, json.getBytes(StandardCharsets.UTF_8))
    file
  }
}
