/*
 * Copyright (C) 2017  Belgrade Center for Digital Humanities
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.humanistika.exist.index.algolia.backend

import akka.actor.{Actor, ActorRef, Props}
import akka.pattern.pipe
import com.algolia.search.{APIClient, ApacheAPIClientBuilder, Index}
import org.humanistika.exist.index.algolia.{CollectionPath, DocumentId, IndexName, IndexableRootObject, LocalIndexableRootObject, UserSpecifiedDocumentId, readObjectId}
import org.humanistika.exist.index.algolia.AlgoliaIndex.{Authentication, DEFAULT_BATCH_SIZE, IndexingSettings}
import org.humanistika.exist.index.algolia.IndexableRootObjectJsonSerializer.{COLLECTION_PATH_FIELD_NAME, DOCUMENT_ID_FIELD_NAME}
import AlgoliaIndexActor._
import IncrementalIndexingManagerActor.{AlgoliaRemoveForCollectionFailed, AlgoliaRemoveForCollectionSucceeded, ConfigureIndex, DropIndexes, IndexChanges, RemoveForCollection, RemoveForDocument}
import com.algolia.search.exceptions.{AlgoliaHttpException, AlgoliaHttpRetriesException}
import com.algolia.search.inputs.BatchOperation
import com.algolia.search.inputs.batch.{BatchAddObjectOperation, BatchDeleteObjectOperation, BatchUpdateObjectOperation}
import com.algolia.search.objects.{IndexSettings, Query}
import com.fasterxml.jackson.databind.ObjectMapper
import grizzled.slf4j.Logger
import org.humanistika.exist.index.algolia.backend.IndexLocalStoreDocumentActor.Changes

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

object AlgoliaIndexManagerActor {
  val ACTOR_NAME = "AlgoliaIndexManager"

  private[algolia] def exactCollectionPathFilter(collectionPath: CollectionPath): String = {
    val escapedCollectionPath = collectionPath
      .replace("\\", "\\\\")
      .replace("\"", "\\\"")
    s"""$COLLECTION_PATH_FIELD_NAME:${'"'}$escapedCollectionPath${'"'}"""
  }

  private[algolia] def exactCollectionPathFacetFilter(collectionPath: CollectionPath): String =
    exactFacetFilter(COLLECTION_PATH_FIELD_NAME, collectionPath)

  private[algolia] def exactDocumentIdFacetFilter(documentId: UserSpecifiedDocumentId): String =
    exactFacetFilter(DOCUMENT_ID_FIELD_NAME, documentId)

  private[algolia] def exactDocumentIdNumericFilter(documentId: DocumentId): String =
    s"$DOCUMENT_ID_FIELD_NAME = $documentId"

  private def exactFacetFilter(attribute: String, value: String): String =
    s"$attribute:$value"
}

class AlgoliaIndexManagerActor extends Actor {
  private val logger = Logger(classOf[AlgoliaIndexManagerActor])
  private var client: Option[APIClient] = None
  private var indexingSettings: IndexingSettings = IndexingSettings()
  private var perIndexActors: Map[IndexName, ActorRef] = Map.empty

  override def receive: Receive = {
    case auth: Authentication =>
      if(logger.isTraceEnabled) {
        logger.trace("Authenticating Algolia API Client");
      }
      this.client = Some(new ApacheAPIClientBuilder(auth.applicationId, auth.adminApiKey).build())
      this.indexingSettings = auth.indexingSettings

    case ConfigureIndex(indexName, maybeBatchSize) =>
      maybeBatchSize.foreach { batchSize =>
        indexingSettings = indexingSettings.copy(perIndexBatchSize = indexingSettings.perIndexBatchSize + (indexName -> batchSize))
        perIndexActors.get(indexName).foreach(_ ! ConfigureBatchSize(batchSize))
      }

    case IndexChanges(indexName, changes) =>
      if(logger.isTraceEnabled) {
        logger.trace(s"Relaying IndexChanges to per-index actors (id=${changes.documentId}, additions=${changes.additions.size}, updates=${changes.updates.size}, deletions=${changes.deletions.size}) for index: $indexName")
      }
      val indexActor = getOrCreatePerIndexActor(indexName)
      indexActor ! changes

    case rfd @ RemoveForDocument(indexName, documentId, userSpecifiedDocumentId, userSpecifiedVisibleBy) =>
      if(logger.isTraceEnabled) {
        logger.trace(s"Initiating RemoveForDocument (id=${documentId}, userSpecificDocId=${userSpecifiedDocumentId}) for index: $indexName")
      }
      val indexActor = getOrCreatePerIndexActor(indexName)
      indexActor ! rfd

    case rfc @ RemoveForCollection(indexName, collectionPath) =>
      if(logger.isTraceEnabled) {
        logger.trace(s"Initiating RemoveForCollection (path=${collectionPath}) for index: $indexName")
      }
      val indexActor = getOrCreatePerIndexActor(indexName)
      indexActor ! rfc

    case DropIndexes =>
      if(logger.isTraceEnabled) {
        logger.trace(s"Initiating DropIndexes");
      }
      for(indexName <- getClientOrThrow.listIndices.asScala.map(_.getName)) {
        val indexActor = getOrCreatePerIndexActor(indexName)
        indexActor ! DropIndex
      }

    case DroppedIndex(indexName: IndexName) =>
      if(logger.isTraceEnabled) {
        logger.trace(s"Dropped Index: $indexName")
      }
      this.perIndexActors = perIndexActors - indexName

    case succeeded: AlgoliaRemoveForCollectionSucceeded =>
      context.parent ! succeeded

    case failed: AlgoliaRemoveForCollectionFailed =>
      context.parent ! failed
  }

  private def getOrCreatePerIndexActor(indexName: String) : ActorRef = perIndexActors.getOrElse(indexName, createPerIndexActor(indexName))

  private def createPerIndexActor(indexName: String): ActorRef = {
    def initIndex(): Index[IndexableRootObject] = {
      val index = getClientOrThrow.initIndex(indexName, classOf[IndexableRootObject])

      val indexSettings = Option(index.getSettings).getOrElse(new IndexSettings)
      val searchableAttributes = Option(indexSettings.getSearchableAttributes).map(_.asScala.toSeq).getOrElse(Seq.empty)
      val attributesForFaceting = Option(indexSettings.getAttributesForFaceting).map(_.asScala.toSeq).getOrElse(Seq.empty)
      val newSearchableAttributes = addSearchableAttributes(searchableAttributes,
        COLLECTION_PATH_FIELD_NAME,
        DOCUMENT_ID_FIELD_NAME)
      val newAttributesForFaceting = addFacetAttribute(attributesForFaceting,
        COLLECTION_PATH_FIELD_NAME,
        DOCUMENT_ID_FIELD_NAME)
      indexSettings.setSearchableAttributes(newSearchableAttributes.asJava)
      indexSettings.setAttributesForFaceting(newAttributesForFaceting.asJava)
      index.setSettings(indexSettings)

      index
    }

    @tailrec
    def addSearchableAttributes(searchableAttributes: Seq[String], searchableAttribute: String*): Seq[String] = {
      searchableAttribute.headOption match {
        case None =>
          searchableAttributes

        case Some(sa) =>
          val newSearchableAttributes = addSearchableAttribute(searchableAttributes, sa)
          if(searchableAttribute.size > 1) {
            addSearchableAttributes(newSearchableAttributes, searchableAttribute.tail:_*)
          } else {
            newSearchableAttributes
          }

      }
    }

    def addSearchableAttribute(searchableAttributes: Seq[String], searchableAttribute: String): Seq[String] = {
      searchableAttributes
        .find(_.equals(searchableAttribute))
        .map(_ => searchableAttributes).getOrElse(searchableAttributes :+ searchableAttribute)
    }

    @tailrec
    def addFacetAttribute(attributesForFaceting: Seq[String], attribute: String*): Seq[String] = {
      attribute.headOption match {
        case None =>
          attributesForFaceting

        case Some(attr) =>
          val newAttributesForFaceting = addSingleFacetAttribute(attributesForFaceting, attr)
          if(attribute.size > 1) {
            addFacetAttribute(newAttributesForFaceting, attribute.tail:_*)
          } else {
            newAttributesForFaceting
          }
      }
    }

    def addSingleFacetAttribute(attributesForFaceting: Seq[String], attribute: String): Seq[String] = {
      val filterOnlyAttribute = s"filterOnly($attribute)"
      attributesForFaceting
        .find(existing => existing.equals(attribute) || existing.equals(filterOnlyAttribute) || existing.equals(s"searchable($attribute)"))
        .map(_ => attributesForFaceting)
        .getOrElse(attributesForFaceting :+ filterOnlyAttribute)
    }

    val perIndexActor = context.actorOf(Props(classOf[AlgoliaIndexActor], indexName, new DefaultAlgoliaIndexClient(initIndex()), indexingSettings.batchSizeFor(indexName)), indexName)
    perIndexActors = perIndexActors + (indexName -> perIndexActor)
    perIndexActor
  }

  private def getClientOrThrow: APIClient = client.getOrElse(throw new IllegalStateException("No Algolia Client configured"))
}

object AlgoliaIndexActor {
  case object DropIndex
  case class DroppedIndex(indexName: IndexName)
  case class ConfigureBatchSize(batchSize: Int)

  // responses to self regarding Algolia operations (in futures)
  type BatchLogMsgGroupId = Long
  case class AlgoliaOperationResponse(operation: PendingOperation, batchLogMsgGroupId: BatchLogMsgGroupId, taskIds: Seq[Long], error: Option[Throwable])

  private[algolia] sealed trait PendingOperation
  private case class BatchChanges(changes: Changes) extends PendingOperation
  private case class DeleteDocument(documentId: DocumentId, userSpecifiedDocumentId: Option[UserSpecifiedDocumentId]) extends PendingOperation
  private case class DeleteCollection(collectionPath: CollectionPath) extends PendingOperation
  private case object DeleteIndex extends PendingOperation

  private[algolia] case class BatchChunkPlan(chunkNumber: Int, totalChunks: Int, additions: Int, updates: Int, deletions: Int)

  private[algolia] def isEmptyBatch(changes: Changes): Boolean =
    changes.additions.isEmpty && changes.updates.isEmpty && changes.deletions.isEmpty

  private[algolia] def planBatchChunks(additionsCount: Int, updatesCount: Int, deletionsCount: Int, batchSize: Int): Seq[BatchChunkPlan] = {
    require(batchSize > 0, "batchSize must be positive")
    val operationKinds =
      Seq.fill(additionsCount)("add") ++
        Seq.fill(updatesCount)("update") ++
        Seq.fill(deletionsCount)("delete")
    val chunks = operationKinds.grouped(batchSize).toSeq
    chunks.zipWithIndex.map { case (chunk, index) =>
      BatchChunkPlan(
        chunkNumber = index + 1,
        totalChunks = chunks.size,
        additions = chunk.count(_ == "add"),
        updates = chunk.count(_ == "update"),
        deletions = chunk.count(_ == "delete")
      )
    }
  }

  private[algolia] def retryable(t: Throwable): Boolean = t match {
    case http: AlgoliaHttpException =>
      http.getHttpResponseCode == 429 || (http.getHttpResponseCode >= 500 && http.getHttpResponseCode < 600)
    case _: AlgoliaHttpRetriesException =>
      true
    case _ =>
      false
  }

  private lazy val mapper = new ObjectMapper
}

private[algolia] trait AlgoliaIndexClient {
  def batch(operations: Seq[BatchOperation]): Long
  def deleteBy(query: Query): Long
  def deleteIndex(): Long
}

private[algolia] class DefaultAlgoliaIndexClient(algoliaIndex: Index[IndexableRootObject]) extends AlgoliaIndexClient {
  override def batch(operations: Seq[BatchOperation]): Long = {
    val task = algoliaIndex.batch(operations.asJava)
    val taskId = Option(task.getTaskID).map(_.longValue()).getOrElse(-1L)
    task.waitForCompletion()
    taskId
  }

  override def deleteBy(query: Query): Long = {
    val task = algoliaIndex.deleteBy(query)
    val taskId = Option(task.getTaskID).map(_.longValue()).getOrElse(-1L)
    task.waitForCompletion()
    taskId
  }

  override def deleteIndex(): Long = {
    val task = algoliaIndex.delete()
    val taskId = Option(task.getTaskID).map(_.longValue()).getOrElse(-1L)
    task.waitForCompletion()
    taskId
  }
}

class AlgoliaIndexActor(indexName: IndexName, algoliaIndexClient: AlgoliaIndexClient, initialBatchSize: Int = DEFAULT_BATCH_SIZE) extends Actor {

  import context.dispatcher

  private val logger = Logger(classOf[AlgoliaIndexActor])
  private var batchSize: Int = math.max(1, initialBatchSize)
  private var activeOperation: Option[PendingOperation] = None
  private var queuedOperations: Vector[PendingOperation] = Vector.empty

  override def receive: Receive = {

    case ConfigureBatchSize(newBatchSize) =>
      this.batchSize = math.max(1, newBatchSize)

    case changes @ Changes(_, _, _, _) =>
      if(logger.isTraceEnabled) {
        logChanges(changes)
      }
      enqueue(BatchChanges(changes))

    case RemoveForDocument(_, documentId, userSpecifiedDocumentId, _) =>
      enqueue(DeleteDocument(documentId, userSpecifiedDocumentId))

    case RemoveForCollection(_, collectionPath) =>
      enqueue(DeleteCollection(collectionPath))

    case DropIndex =>
      enqueue(DeleteIndex)

    case AlgoliaOperationResponse(operation, batchLogMsgGroupId, taskIds, Some(t)) =>
      logFailure(operation, batchLogMsgGroupId, t)
      operation match {
        case DeleteCollection(collectionPath) =>
          context.parent ! AlgoliaRemoveForCollectionFailed(indexName, collectionPath, t)
        case _ =>
      }
      activeOperation = None
      processNext()

    case AlgoliaOperationResponse(operation, batchLogMsgGroupId, taskIds, None) =>
      logSuccess(operation, batchLogMsgGroupId, taskIds)
      operation match {
        case DeleteCollection(collectionPath) =>
          context.parent ! AlgoliaRemoveForCollectionSucceeded(indexName, collectionPath)
        case DeleteIndex =>
          context.parent ! DroppedIndex(indexName)
          context.stop(self)
        case _ =>
      }
      activeOperation = None
      processNext()
  }

  private def enqueue(operation: PendingOperation): Unit = {
    queuedOperations = queuedOperations :+ operation
    processNext()
  }

  private def processNext(): Unit = {
    if (activeOperation.isEmpty) {
      queuedOperations.headOption.foreach { operation =>
        queuedOperations = queuedOperations.tail
        activeOperation = Some(operation)
        run(operation)
      }
    }
  }

  private def run(operation: PendingOperation): Unit = {
    val batchLogMsgGroupId = System.nanoTime()
    val effectiveBatchSize = batchSize
    logStart(operation, batchLogMsgGroupId)
    val future = Future {
      Try(withRetry(operationDescription(operation)) {
        execute(operation, effectiveBatchSize)
      }) match {
        case Success(taskIds) =>
          AlgoliaOperationResponse(operation, batchLogMsgGroupId, taskIds, None)
        case Failure(t) =>
          AlgoliaOperationResponse(operation, batchLogMsgGroupId, Seq.empty, Some(t))
      }
    }.recover {
      case t: Throwable =>
        AlgoliaOperationResponse(operation, batchLogMsgGroupId, Seq.empty, Some(t))
    }
    pipe(future).to(self)
  }

  private def execute(operation: PendingOperation, effectiveBatchSize: Int): Seq[Long] = operation match {
    case BatchChanges(changes) =>
      executeBatchChanges(changes, effectiveBatchSize)

    case DeleteDocument(documentId, userSpecifiedDocumentId) =>
      val query = new Query()
      userSpecifiedDocumentId match {
        case Some(userSpecifiedId) =>
          query.setFacetFilters(List(AlgoliaIndexManagerActor.exactDocumentIdFacetFilter(userSpecifiedId)).asJava)
        case None =>
          query.setNumericFilters(List(AlgoliaIndexManagerActor.exactDocumentIdNumericFilter(documentId)).asJava)
      }
      Seq(algoliaIndexClient.deleteBy(query))

    case DeleteCollection(collectionPath) =>
      val query = new Query()
      query.setFacetFilters(List(AlgoliaIndexManagerActor.exactCollectionPathFacetFilter(collectionPath)).asJava)
      Seq(algoliaIndexClient.deleteBy(query))

    case DeleteIndex =>
      Seq(algoliaIndexClient.deleteIndex())
  }

  private def executeBatchChanges(changes: Changes, effectiveBatchSize: Int): Seq[Long] = {
    if (AlgoliaIndexActor.isEmptyBatch(changes)) {
      logger.debug(s"Skipping Algolia batch for documentId=${changes.documentId} in index: $indexName because there are no additions, updates, or deletions")
      Seq.empty
    } else {
      val operations: Seq[BatchOperation] =
        changes.additions.map(new BatchAddObjectOperation[LocalIndexableRootObject](_)) ++
          changes.updates.map(new BatchUpdateObjectOperation[LocalIndexableRootObject](_)) ++
          changes.deletions.map(new BatchDeleteObjectOperation(_))

      val chunks = operations.grouped(effectiveBatchSize).toSeq
      val plans = AlgoliaIndexActor.planBatchChunks(changes.additions.size, changes.updates.size, changes.deletions.size, effectiveBatchSize)
      chunks.zip(plans).map { case (chunk, plan) =>
        logger.info(s"Sending changes chunk ${plan.chunkNumber}/${plan.totalChunks} (additions=${plan.additions} updates=${plan.updates} deletions=${plan.deletions}) to Algolia for documentId=${changes.documentId} in index: $indexName")
        val taskId = algoliaIndexClient.batch(chunk)
        logger.info(s"Sent changes chunk ${plan.chunkNumber}/${plan.totalChunks} (taskId=$taskId) to Algolia for documentId=${changes.documentId} in index: $indexName")
        taskId
      }
    }
  }

  private def withRetry[A](description: String)(operation: => A): A = {
    val maxAttempts = 3
    val baseDelayMillis = 1000L

    @tailrec
    def attempt(number: Int): A = {
      Try(operation) match {
        case Success(value) =>
          value
        case Failure(t) if number < maxAttempts && AlgoliaIndexActor.retryable(t) =>
          val delay = baseDelayMillis * math.pow(2, number - 1).toLong
          logger.warn(s"Retrying Algolia operation '$description' for index: $indexName after failure on attempt $number/$maxAttempts; delayMillis=$delay", t)
          Thread.sleep(delay)
          attempt(number + 1)
        case Failure(t) =>
          throw t
      }
    }

    attempt(1)
  }

  private def operationDescription(operation: PendingOperation): String = operation match {
    case BatchChanges(changes) => s"batch documentId=${changes.documentId}"
    case DeleteDocument(documentId, userSpecifiedDocumentId) => s"delete documentId=$documentId userSpecifiedDocumentId=$userSpecifiedDocumentId"
    case DeleteCollection(collectionPath) => s"delete collectionPath=$collectionPath"
    case DeleteIndex => "delete index"
  }

  private def logStart(operation: PendingOperation, batchLogMsgGroupId: BatchLogMsgGroupId): Unit = operation match {
    case BatchChanges(changes) =>
      logger.info(s"Sending changes (msgId=$batchLogMsgGroupId) (additions=${changes.additions.size} updates=${changes.updates.size} deletions=${changes.deletions.size} batchSize=$batchSize) to Algolia for documentId=${changes.documentId} in index: $indexName")
    case DeleteDocument(documentId, userSpecifiedDocumentId) =>
      logger.info(s"Sending remove document (msgId=$batchLogMsgGroupId) to Algolia for documentId=$documentId, userSpecificDocId=$userSpecifiedDocumentId in index: $indexName")
    case DeleteCollection(collectionPath) =>
      logger.info(s"Sending remove documents for Collection (msgId=$batchLogMsgGroupId) (path=$collectionPath) from index: $indexName to Algolia")
    case DeleteIndex =>
      logger.info(s"Sending remove index (msgId=$batchLogMsgGroupId) for index: $indexName to Algolia")
  }

  private def logSuccess(operation: PendingOperation, batchLogMsgGroupId: BatchLogMsgGroupId, taskIds: Seq[Long]): Unit = operation match {
    case BatchChanges(changes) =>
      logger.info(s"Sent changes (msgId=$batchLogMsgGroupId) taskIds=${taskIds.mkString(",")} for documentId=${changes.documentId} in index: $indexName to Algolia")
    case DeleteDocument(documentId, userSpecifiedDocumentId) =>
      logger.info(s"Sent remove document (msgId=$batchLogMsgGroupId) taskIds=${taskIds.mkString(",")} for documentId=${documentId}, userSpecifiedDocumentId=$userSpecifiedDocumentId in index: $indexName to Algolia")
    case DeleteCollection(collectionPath) =>
      logger.info(s"Sent remove documents for Collection (msgId=$batchLogMsgGroupId) taskIds=${taskIds.mkString(",")} (path=$collectionPath) in index: $indexName to Algolia")
    case DeleteIndex =>
      logger.info(s"Sent remove index (msgId=$batchLogMsgGroupId) taskIds=${taskIds.mkString(",")} for index: $indexName to Algolia")
  }

  private def logFailure(operation: PendingOperation, batchLogMsgGroupId: BatchLogMsgGroupId, t: Throwable): Unit = {
    val detail = operationDescription(operation)
    logger.error(s"ALGOLIA_INDEXING_STATUS status=degraded index=$indexName operation='$detail' msgId=$batchLogMsgGroupId", t)
  }

  private def logChanges(changes: Changes) {
    if(logger.isTraceEnabled) {
      for (addition <- changes.additions) {
        logger.trace(s"Adding object(id=${readObjectId(addition.path, mapper)} path=${addition.path}) to index: $indexName")
      }

      for (update <- changes.updates) {
        logger.trace(s"Updating object(id=${readObjectId(update.path, mapper)} path=${update.path}) to index: $indexName")
      }

      for (removal <- changes.deletions) {
        logger.trace(s"Removing object(id=${removal}) from index: $indexName")
      }
    }
  }
}
