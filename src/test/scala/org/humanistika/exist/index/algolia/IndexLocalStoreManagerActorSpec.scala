package org.humanistika.exist.index.algolia

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import akka.actor.{Actor, Props}
import com.fasterxml.jackson.databind.ObjectMapper
import org.exist.util.FileUtils
import org.humanistika.exist.index.algolia.backend.{IndexLocalStoreActor, IndexingStatusStore}
import org.humanistika.exist.index.algolia.backend.IndexLocalStoreManagerActor.collectionPathMatchesTree
import org.humanistika.exist.index.algolia.backend.IndexLocalStoreDocumentActor.{Changes, FindChanges}
import org.humanistika.exist.index.algolia.backend.IncrementalIndexingManagerActor.{CollectionDeletesApplied, FlushPendingCollectionRemovals, IndexChanges, RemoveForCollection, RemovedCollection}
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
    "emit object-level collection deletions on flush and preserve local evidence until success acknowledgement" in new AkkaTestkitSpecs2Support {
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
        expectNoMessage(200.millis)

        actor ! FlushPendingCollectionRemovals
        val changes = expectMsgType[IndexChanges]
        changes.indexName mustEqual indexName
        changes.changes.deletions must contain(exactly("target.json", "child.json"))
        changes.changes.collectionDeletionPath must beSome(targetCollection)
        Files.exists(targetDocFile) must beTrue
        Files.exists(childDocFile) must beTrue
        Files.exists(siblingDocFile) must beTrue

        actor ! CollectionDeletesApplied(indexName, targetCollection)
        expectMsg(RemovedCollection(indexName, targetCollection))
        awaitCond(!Files.exists(targetDocFile), max = 1.second)
        awaitCond(!Files.exists(childDocFile), max = 1.second)
        Files.exists(siblingDocFile) must beTrue
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

  "IndexLocalStoreDocumentActor" should {
    "treat a quarantined snapshot as full additions on the next reindex" in new AkkaTestkitSpecs2Support {
      val indexDir = Files.createTempDirectory("algolia-index-local-store-document")
      val quarantineDir = Files.createTempDirectory("algolia-index-local-store-quarantine")
      try {
        val documentDirName = "1"
        val oldFile = writeStoredRootObject(indexDir, "", documentDirName, 100L, "target.json", "/db/apps/raskovnik-data/data/GE.RKMD")
        Files.createDirectories(quarantineDir)
        Files.move(indexDir.resolve(documentDirName), quarantineDir.resolve(documentDirName))
        val quarantinedOldFile = quarantineDir.resolve(documentDirName).resolve("100").resolve("target.json")

        val newFile = writeStoredRootObject(indexDir, "", documentDirName, 200L, "target.json", "/db/apps/raskovnik-data/data/GE.RKMD")
        val actor = system.actorOf(Props(classOf[org.humanistika.exist.index.algolia.backend.IndexLocalStoreDocumentActor], indexDir, 1))

        Files.exists(oldFile) must beFalse
        Files.exists(quarantinedOldFile) must beTrue
        Files.exists(newFile) must beTrue

        actor ! FindChanges(200L, None, 1)
        val changes = expectMsgType[Changes]
        changes.additions.map(_.path.getFileName.toString) must contain(exactly("target.json"))
        changes.updates must beEmpty
        changes.deletions must beEmpty
        changes.collectionPath must beSome("/db/apps/raskovnik-data/data/GE.RKMD")
      } finally {
        FileUtils.deleteQuietly(indexDir)
        FileUtils.deleteQuietly(quarantineDir)
      }
    }
  }

  private def writeStoredRootObject(indexesDir: Path, indexName: String, documentDir: String, timestamp: Long, filename: String, collectionPath: String): Path = {
    val timestampDir =
      if (indexName.isEmpty) indexesDir.resolve(documentDir).resolve(timestamp.toString)
      else indexesDir.resolve(indexName).resolve(documentDir).resolve(timestamp.toString)
    Files.createDirectories(timestampDir)

    val file = timestampDir.resolve(filename)
    val json =
      s"""{"objectID":"$filename","collection":"$collectionPath","documentID":"$documentDir"}"""
    Files.write(file, json.getBytes(StandardCharsets.UTF_8))
    file
  }
}
