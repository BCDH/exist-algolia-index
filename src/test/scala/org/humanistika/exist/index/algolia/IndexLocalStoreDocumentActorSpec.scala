package org.humanistika.exist.index.algolia

import java.nio.file.{Files, Path}

import akka.actor.{Actor, ActorRef, Props}
import org.exist.util.FileUtils
import org.humanistika.exist.index.algolia.backend.IndexLocalStoreDocumentActor
import org.humanistika.exist.index.algolia.backend.IndexLocalStoreDocumentActor.{Changes, FindChanges, RemoveDocument, RemovedDocument, Write}
import org.specs2.mutable.Specification

class IndexLocalStoreDocumentActorSpec extends Specification {

  "IndexLocalStoreDocumentActor.sameChecksum" should {
    "treat identical checksum bytes as unchanged" in {
      IndexLocalStoreDocumentActor.sameChecksum(
        Right(Array[Byte](1, 2, 3)),
        Right(Array[Byte](1, 2, 3))
      ) must beRight(true)
    }

    "treat different checksum bytes as changed" in {
      IndexLocalStoreDocumentActor.sameChecksum(
        Right(Array[Byte](1, 2, 3)),
        Right(Array[Byte](1, 2, 4))
      ) must beRight(false)
    }

    "return checksum failures instead of pretending there was an update" in {
      val checksumFailure = new IllegalStateException("boom")
      IndexLocalStoreDocumentActor.sameChecksum(
        Left(checksumFailure),
        Right(Array[Byte](1, 2, 3))
      ) must beLeft(Seq(checksumFailure))
    }
  }

  "IndexLocalStoreDocumentActor.getLatestTimestampDir" should {
    "return none for a missing directory" in {
      val missingDir = Files.createTempDirectory("algolia-index-local-store-missing").resolve("missing")
      IndexLocalStoreDocumentActor.getLatestTimestampDir(missingDir) must beNone
    }
  }

  "IndexLocalStoreDocumentActor" should {
    "emit RemovedDocument when remove is requested for missing prior state" in new AkkaTestkitSpecs2Support {
      withDocumentActorHarness(this) { harness =>
        harness ! RemoveDocument(42, None, None)
        expectMsg(RemovedDocument(42))
      }
    }

    "treat the first stored snapshot as additions when no previous state exists" in new AkkaTestkitSpecs2Support {
      withDocumentActorHarness(this) { harness =>
        val timestamp = 1710000000000L
        harness ! Write(timestamp, testRootObject(documentId = 42, nodeId = "1.5.2.2.4"))
        harness ! FindChanges(timestamp, None, 42)

        val changes = expectMsgType[Changes]
        changes.documentId mustEqual 42
        changes.additions.map(_.path.getFileName.toString) must contain(exactly("1.5.2.2.4.json"))
        changes.updates must beEmpty
        changes.deletions must beEmpty
      }
    }

    "not throw when FindChanges is requested without current local state" in new AkkaTestkitSpecs2Support {
      withDocumentActorHarness(this) { harness =>
        harness ! FindChanges(1710000000000L, None, 42)
        expectMsg(Changes(42, Seq.empty, Seq.empty, Seq.empty))

        harness ! RemoveDocument(42, None, None)
        expectMsg(RemovedDocument(42))
      }
    }
  }

  private def testRootObject(documentId: DocumentId, nodeId: String): IndexableRootObject =
    IndexableRootObject(
      collectionPath = "/db/apps/raskovnik-data/data/MBRT.RDG",
      collectionId = 7,
      documentId = documentId,
      userSpecifiedDocumentId = None,
      userSpecifiedVisibleBy = None,
      nodeId = Some(nodeId),
      userSpecifiedNodeId = None,
      children = Seq.empty
    )

  private def withDocumentActorHarness[T](kit: AkkaTestkitSpecs2Support)(f: ActorRef => T): T = {
    import kit._

    val indexDir = Files.createTempDirectory("algolia-index-local-store-doc")
    try {
      val harness = system.actorOf(Props(new Actor {
        private val child = context.actorOf(Props(classOf[IndexLocalStoreDocumentActor], indexDir, 42), "document")

        override def receive: Receive = {
          case removed: RemovedDocument if sender() == child =>
            testActor ! removed
          case msg =>
            child.forward(msg)
        }
      }))
      f(harness)
    } finally {
      FileUtils.deleteQuietly(indexDir)
    }
  }
}
