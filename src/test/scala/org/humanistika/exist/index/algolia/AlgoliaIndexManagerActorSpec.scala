package org.humanistika.exist.index.algolia

import java.nio.file.Files
import java.util.concurrent.CountDownLatch

import akka.actor.{Actor, ActorRef, Props}
import com.algolia.search.exceptions.AlgoliaHttpException
import com.algolia.search.inputs.BatchOperation
import com.algolia.search.objects.Query
import org.humanistika.exist.index.algolia.IndexableRootObjectJsonSerializer.COLLECTION_PATH_FIELD_NAME
import org.humanistika.exist.index.algolia.backend.AlgoliaIndexActor.{DroppedIndex, DropIndex}
import org.humanistika.exist.index.algolia.backend.IncrementalIndexingManagerActor.{AlgoliaRemoveForCollectionSucceeded, RemoveForCollection}
import org.humanistika.exist.index.algolia.backend.{AlgoliaIndexActor, AlgoliaIndexClient}
import org.humanistika.exist.index.algolia.backend.AlgoliaIndexManagerActor.exactCollectionPathFilter
import org.humanistika.exist.index.algolia.backend.IndexLocalStoreDocumentActor.Changes
import org.specs2.mutable.Specification

class AlgoliaIndexManagerActorSpec extends Specification {

  "exactCollectionPathFilter" should {
    "build an exact filter for the collection field" in {
      exactCollectionPathFilter("/db/apps/raskovnik-data/data/MBRT.RDG") mustEqual
        s"""$COLLECTION_PATH_FIELD_NAME:${'"'}/db/apps/raskovnik-data/data/MBRT.RDG${'"'}"""
    }

    "escape quotes in collection paths" in {
      exactCollectionPathFilter("/db/apps/\"quoted\"") mustEqual
        s"""$COLLECTION_PATH_FIELD_NAME:${'"'}/db/apps/\\\"quoted\\\"${'"'}"""
    }
  }

  "AlgoliaIndexActor" should {
    "treat empty change sets as no-op batches" in {
      AlgoliaIndexActor.isEmptyBatch(Changes(42, Seq.empty, Seq.empty, Seq.empty)) must beTrue
    }

    "treat any actual addition, update, or deletion as non-empty" in {
      val tempFile = Files.createTempFile("algolia-index-batch", ".json")
      try {
        val rootObject = LocalIndexableRootObject(tempFile)

        AlgoliaIndexActor.isEmptyBatch(Changes(42, Seq(rootObject), Seq.empty, Seq.empty)) must beFalse
        AlgoliaIndexActor.isEmptyBatch(Changes(42, Seq.empty, Seq(rootObject), Seq.empty)) must beFalse
        AlgoliaIndexActor.isEmptyBatch(Changes(42, Seq.empty, Seq.empty, Seq("object-id"))) must beFalse
      } finally {
        Files.deleteIfExists(tempFile)
      }
    }

    "plan large change sets into bounded chunks" in {
      AlgoliaIndexActor.planBatchChunks(additionsCount = 5, updatesCount = 2, deletionsCount = 1, batchSize = 3) mustEqual Seq(
        AlgoliaIndexActor.BatchChunkPlan(1, 3, additions = 3, updates = 0, deletions = 0),
        AlgoliaIndexActor.BatchChunkPlan(2, 3, additions = 2, updates = 1, deletions = 0),
        AlgoliaIndexActor.BatchChunkPlan(3, 3, additions = 0, updates = 1, deletions = 1)
      )
    }

    "classify Algolia rate-limit and service errors as retryable" in {
      AlgoliaIndexActor.retryable(new AlgoliaHttpException(429, "rate limit")) must beTrue
      AlgoliaIndexActor.retryable(new AlgoliaHttpException(503, "unavailable")) must beTrue
      AlgoliaIndexActor.retryable(new AlgoliaHttpException(403, "quota")) must beFalse
    }

    "send large change sets as bounded batches" in new AkkaTestkitSpecs2Support {
      val client = new RecordingAlgoliaClient
      val actor = system.actorOf(Props(classOf[AlgoliaIndexActor], "ras", client, 2))
      val tempFiles = (1 to 5).map(_ => Files.createTempFile("algolia-index-batch", ".json"))

      try {
        actor ! Changes(42, tempFiles.map(LocalIndexableRootObject(_)), Seq.empty, Seq.empty)
        awaitCond(client.batchSizes == Vector(2, 2, 1))
        success
      } finally {
        tempFiles.foreach(Files.deleteIfExists)
      }
    }

    "acknowledge collection removal only after the delete completes" in new AkkaTestkitSpecs2Support {
      val deleteStarted = new CountDownLatch(1)
      val allowDeleteToFinish = new CountDownLatch(1)
      val client = new BlockingDeleteAlgoliaClient(deleteStarted, allowDeleteToFinish)

      val harness = actorHarness("ras", client, 1000)(this)
      harness ! RemoveForCollection("ras", "/db/apps/raskovnik-data/data/VSK.SR")

      awaitCond(deleteStarted.getCount == 0)
      expectNoMessage()
      allowDeleteToFinish.countDown()
      expectMsg(AlgoliaRemoveForCollectionSucceeded("ras", "/db/apps/raskovnik-data/data/VSK.SR"))
    }

    "acknowledge index drop only after the delete completes" in new AkkaTestkitSpecs2Support {
      val deleteStarted = new CountDownLatch(1)
      val allowDeleteToFinish = new CountDownLatch(1)
      val client = new BlockingDeleteAlgoliaClient(deleteStarted, allowDeleteToFinish)

      val harness = actorHarness("ras", client, 1000)(this)
      harness ! DropIndex

      awaitCond(deleteStarted.getCount == 0)
      expectNoMessage()
      allowDeleteToFinish.countDown()
      expectMsg(DroppedIndex("ras"))
    }
  }

  private class RecordingAlgoliaClient extends AlgoliaIndexClient {
    @volatile var batchSizes: Vector[Int] = Vector.empty

    override def batch(operations: Seq[BatchOperation]): Long = {
      batchSizes = batchSizes :+ operations.size
      batchSizes.size.toLong
    }

    override def deleteBy(query: Query): Long = 1L
    override def deleteIndex(): Long = 1L
  }

  private class BlockingDeleteAlgoliaClient(deleteStarted: CountDownLatch, allowDeleteToFinish: CountDownLatch) extends AlgoliaIndexClient {
    override def batch(operations: Seq[BatchOperation]): Long = 1L

    override def deleteBy(query: Query): Long = {
      deleteStarted.countDown()
      allowDeleteToFinish.await()
      10L
    }

    override def deleteIndex(): Long = {
      deleteStarted.countDown()
      allowDeleteToFinish.await()
      11L
    }
  }

  private def actorHarness(indexName: String, client: AlgoliaIndexClient, batchSize: Int)(implicit kit: AkkaTestkitSpecs2Support): ActorRef = {
    import kit._

    system.actorOf(Props(new Actor {
      private val child = context.actorOf(Props(classOf[AlgoliaIndexActor], indexName, client, batchSize), s"algolia-$indexName")

      override def receive: Receive = {
        case msg @ (_: AlgoliaRemoveForCollectionSucceeded | _: DroppedIndex) if sender() == child =>
          testActor ! msg
        case msg =>
          child.forward(msg)
      }
    }))
  }
}
