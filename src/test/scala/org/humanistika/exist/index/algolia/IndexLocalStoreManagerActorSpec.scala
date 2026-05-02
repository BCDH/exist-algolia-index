package org.humanistika.exist.index.algolia

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import akka.actor.{Actor, Props}
import com.fasterxml.jackson.databind.ObjectMapper
import org.exist.util.FileUtils
import org.humanistika.exist.index.algolia.backend.{IndexLocalStoreActor, IndexingStatusStore}
import org.humanistika.exist.index.algolia.backend.IndexLocalStoreManagerActor.collectionPathMatchesTree
import org.humanistika.exist.index.algolia.backend.IncrementalIndexingManagerActor.{FlushPendingCollectionRemovals, IndexChanges, RemoveForCollection, RemovedCollection}
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
    "defer collection-tree removals until flush and emit object-level deletions" in new AkkaTestkitSpecs2Support {
      val indexesDir = Files.createTempDirectory("algolia-index-local-store-index")
      try {
        val indexName = "ras"
        val targetCollection = "/db/apps/raskovnik-data/data/MBRT.RDG"
        val targetDocFile = writeStoredRootObject(indexesDir, indexName, "doc-target", 100L, "target.json", targetCollection)
        val childDocFile = writeStoredRootObject(indexesDir, indexName, "doc-child", 100L, "child.json", s"$targetCollection/sub")
        val siblingDocFile = writeStoredRootObject(indexesDir, indexName, "doc-sibling", 100L, "sibling.json", "/db/apps/raskovnik-data/data/MBRT.RDG2")

        val actor = system.actorOf(Props(new Actor {
          private val child = context.actorOf(Props(classOf[IndexLocalStoreActor], indexesDir, indexName))

          override def receive: Receive = {
            case removed: RemovedCollection if sender() == child =>
              testActor ! removed
            case changes: IndexChanges if sender() == child =>
              testActor ! changes
            case msg =>
              child.forward(msg)
          }
        }))
        actor ! RemoveForCollection(indexName, targetCollection)

        Files.exists(targetDocFile) must beTrue
        Files.exists(childDocFile) must beTrue
        Files.exists(siblingDocFile) must beTrue
        expectMsg(RemovedCollection(indexName, targetCollection))

        actor ! FlushPendingCollectionRemovals
        awaitCond(!Files.exists(targetDocFile), max = 1.second)
        awaitCond(!Files.exists(childDocFile), max = 1.second)
        Files.exists(siblingDocFile) must beTrue
        val changes = expectMsgType[IndexChanges]
        changes.indexName mustEqual indexName
        changes.changes.deletions must contain(exactly("target.json", "child.json"))
      } finally {
        FileUtils.deleteQuietly(indexesDir)
      }
    }

    "report stale local store status when collection removal has no local backfill" in new AkkaTestkitSpecs2Support {
      val dataDir = Files.createTempDirectory("algolia-index-status")
      val indexesDir = Files.createTempDirectory("algolia-index-local-store-index")
      try {
        val indexName = "ras"
        val targetCollection = "/db/apps/raskovnik-data/data/VSK.SR"
        val statusStore = IndexingStatusStore(dataDir)
        val actor = system.actorOf(Props(new Actor {
          private val child = context.actorOf(Props(classOf[IndexLocalStoreActor], indexesDir, indexName, statusStore), indexName)

          override def receive: Receive = {
            case removed: RemovedCollection if sender() == child =>
              testActor ! removed
            case msg =>
              child.forward(msg)
          }
        }))

        actor ! RemoveForCollection(indexName, targetCollection)
        expectMsg(RemovedCollection(indexName, targetCollection))

        val statusFile = dataDir.resolve("algolia-index").resolve("status.json")
        awaitCond(Files.isRegularFile(statusFile), max = 1.second)
        val records = new ObjectMapper().readTree(statusFile.toFile).get("records")
        records.get(0).get("index").asText() mustEqual indexName
        records.get(0).get("collection").asText() mustEqual targetCollection
        records.get(0).get("state").asText() mustEqual IndexingStatusStore.STALE_LOCAL_STORE
      } finally {
        FileUtils.deleteQuietly(dataDir)
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
