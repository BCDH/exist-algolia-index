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
import org.humanistika.exist.index.algolia.{DocumentId, IndexName, IndexableRootObject, LocalIndexableRootObject, UserSpecifiedDocumentId, readObjectId}
import org.humanistika.exist.index.algolia.AlgoliaIndex.Authentication
import org.humanistika.exist.index.algolia.IndexableRootObjectJsonSerializer.{COLLECTION_PATH_FIELD_NAME, DOCUMENT_ID_FIELD_NAME}
import AlgoliaIndexActor._
import IncrementalIndexingManagerActor.{DropIndexes, IndexChanges, RemoveForCollection, RemoveForDocument}
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
}

class AlgoliaIndexManagerActor extends Actor {
  private val logger = Logger(classOf[AlgoliaIndexManagerActor])
  private var client: Option[APIClient] = None
  private var perIndexActors: Map[IndexName, ActorRef] = Map.empty

  override def receive: Receive = {
    case auth: Authentication =>
      if(logger.isTraceEnabled) {
        logger.trace("Authenticating Algolia API Client");
      }
      this.client = Some(new ApacheAPIClientBuilder(auth.applicationId, auth.adminApiKey).build())

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
  }

  private def getOrCreatePerIndexActor(indexName: String) : ActorRef = perIndexActors.getOrElse(indexName, createPerIndexActor(indexName))

  private def createPerIndexActor(indexName: String): ActorRef = {
    def initIndex(): Index[IndexableRootObject] = {
      val index = getClientOrThrow.initIndex(indexName, classOf[IndexableRootObject])

      val indexSettings = Option(index.getSettings).getOrElse(new IndexSettings)
      val searchableAttributes = Option(indexSettings.getSearchableAttributes).map(_.asScala.toSeq).getOrElse(Seq.empty)
      val newSearchableAttributes = addSearchableAttributes(searchableAttributes,
        COLLECTION_PATH_FIELD_NAME,
        DOCUMENT_ID_FIELD_NAME)
      index.setSettings(indexSettings.setSearchableAttributes(newSearchableAttributes.asJava))

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

    val perIndexActor = context.actorOf(Props(classOf[AlgoliaIndexActor], indexName, initIndex()), indexName)
    perIndexActors = perIndexActors + (indexName -> perIndexActor)
    perIndexActor
  }

  private def getClientOrThrow: APIClient = client.getOrElse(throw new IllegalStateException("No Algolia Client configured"))
}

object AlgoliaIndexActor {
  case object DropIndex
  case class DroppedIndex(indexName: IndexName)

  // responses to self regarding Algolia operations (in futures)
  type BatchLogMsgGroupId = Long
  case class AlgoliaBatchChangesResponse(documentId: DocumentId, batchLogMsgGroupId: BatchLogMsgGroupId, error: Option[Throwable])
  case class AlgoliaDeleteDocumentResponse(documentId: DocumentId, userSpecifiedDocumentId: Option[UserSpecifiedDocumentId], batchLogMsgGroupId: BatchLogMsgGroupId, error: Option[Throwable])
  case class AlgoliaDeleteCollectionResponse(collectionPath: String, batchLogMsgGroupId: BatchLogMsgGroupId, error: Option[Throwable])
  case class AlgoliaDeleteIndexResponse(batchLogMsgGroupId: BatchLogMsgGroupId, error: Option[Throwable])

  private lazy val mapper = new ObjectMapper
}

class AlgoliaIndexActor(indexName: IndexName, algoliaIndex: Index[IndexableRootObject]) extends Actor {

  import context.dispatcher

  private val logger = Logger(classOf[AlgoliaIndexActor])

  override def receive: Receive = {

    case changes @ Changes(_, additions, updates, removals) =>
      if(logger.isTraceEnabled) {
        logChanges(changes)
      }

      val batchOperations =
        additions.map(new BatchAddObjectOperation[LocalIndexableRootObject](_)) ++
          updates.map(new BatchUpdateObjectOperation[LocalIndexableRootObject](_)) ++
          removals.map(new BatchDeleteObjectOperation(_))

      val batchLogMsgGroupId: BatchLogMsgGroupId = System.nanoTime()

      logger.info(s"Sending changes (msgId=$batchLogMsgGroupId) (" +
          s"additions=${batchOperations.count(_.isInstanceOf[BatchAddObjectOperation[_]])} " +
          s"updates=${batchOperations.count(_.isInstanceOf[BatchUpdateObjectOperation[_]])} " +
          s"deletions=${batchOperations.count(_.isInstanceOf[BatchDeleteObjectOperation])})" +
          s" to Algolia for documentId=${changes.documentId} in index: $indexName")

      val batchUpload: Future[AlgoliaBatchChangesResponse] = Future {
        // actually send the data to Algolia!
        Try(algoliaIndex.batch(batchOperations.asJava).waitForCompletion()) match {
          case Success(_) =>
            AlgoliaBatchChangesResponse(changes.documentId, batchLogMsgGroupId, None)
          case Failure(t) =>
            AlgoliaBatchChangesResponse(changes.documentId, batchLogMsgGroupId, Some(t))
        }
      }.recover { case t: Throwable =>
        AlgoliaBatchChangesResponse(changes.documentId, batchLogMsgGroupId, Some(t))
      }

      // redirect the response of the future back to ourselves
      pipe(batchUpload).to(self)

    case AlgoliaBatchChangesResponse(documentId, batchLogMsgGroupId, Some(t)) =>
      logger.error(s"Unable to send changes (msgId=$batchLogMsgGroupId) for documentId=${documentId} in index: $indexName to Algolia", t)

    case AlgoliaBatchChangesResponse(documentId, batchLogMsgGroupId, None) =>
      logger.info(s"Sent changes (msgId=$batchLogMsgGroupId) for documentId=${documentId} in index: $indexName to Algolia")



    case RemoveForDocument(_, documentId, userSpecifiedDocumentId, userSpecifiedVisibleBy) =>
      val batchLogMsgGroupId: BatchLogMsgGroupId = System.nanoTime()

      logger.info(s"Sending remove document (msgId=$batchLogMsgGroupId) to Algolia for documentId=$documentId, userSpecificDocId=$userSpecifiedDocumentId in index: $indexName")

      val deleteDocumentQuery: Future[AlgoliaDeleteDocumentResponse] = Future {
        // actually send the data to Algolia!
        try {
          val query = new Query()
          query.setRestrictSearchableAttributes(List(DOCUMENT_ID_FIELD_NAME).asJava)
          query.setQuery(userSpecifiedDocumentId.getOrElse(documentId.toString))
          algoliaIndex.deleteByQuery(query)
          AlgoliaDeleteDocumentResponse(documentId, userSpecifiedDocumentId, batchLogMsgGroupId, None)
        } catch {
          case t: Throwable =>
            AlgoliaDeleteDocumentResponse(documentId, userSpecifiedDocumentId, batchLogMsgGroupId, Some(t))
        }
      }.recover { case t: Throwable =>
        AlgoliaDeleteDocumentResponse(documentId, userSpecifiedDocumentId, batchLogMsgGroupId, Some(t))
      }

      // redirect the response of the future back to ourselves
      pipe(deleteDocumentQuery).to(self)

    case AlgoliaDeleteDocumentResponse(documentId, userSpecifiedDocumentId, batchLogMsgGroupId, Some(t)) =>
      logger.error(s"Unable to remove document (msgId=$batchLogMsgGroupId) for documentId=${documentId}, userSpecifiedDocumentId=$userSpecifiedDocumentId in index: $indexName from Algolia", t)

    case AlgoliaDeleteDocumentResponse(documentId, userSpecifiedDocumentId, batchLogMsgGroupId, None) =>
      logger.info(s"Sent remove document (msgId=$batchLogMsgGroupId) for documentId=${documentId}, userSpecifiedDocumentId=$userSpecifiedDocumentId in index: $indexName to Algolia")



    case RemoveForCollection(_, collectionPath) =>
      val batchLogMsgGroupId: BatchLogMsgGroupId = System.nanoTime()

      logger.info(s"Sending remove documents for Collection (msgId=$batchLogMsgGroupId) (path=$collectionPath) from index: $indexName to Algolia")

      val deleteCollectionQuery: Future[AlgoliaDeleteCollectionResponse] = Future {
        try {
          val query = new Query()
          query.setRestrictSearchableAttributes(List(COLLECTION_PATH_FIELD_NAME).asJava)
          query.setQuery(collectionPath)
          algoliaIndex.deleteByQuery(query)
          AlgoliaDeleteCollectionResponse(collectionPath, batchLogMsgGroupId, None)
        } catch {
          case t: Throwable =>
            AlgoliaDeleteCollectionResponse(collectionPath, batchLogMsgGroupId, Some(t))
        }
      }.recover {
        case t: Throwable =>
          AlgoliaDeleteCollectionResponse(collectionPath, batchLogMsgGroupId, Some(t))
      }

      // redirect the response of the future back to ourselves
      pipe(deleteCollectionQuery).to(self)

    case AlgoliaDeleteCollectionResponse(collectionPath, batchLogMsgGroupId, Some(t)) =>
      logger.error(s"Unable to remove documents for Collection (msgId=$batchLogMsgGroupId) (path=$collectionPath) in index: $indexName from Algolia", t)

    case AlgoliaDeleteCollectionResponse(collectionPath, batchLogMsgGroupId, None) =>
      logger.info(s"Sent remove documents for Collection (msgId=$batchLogMsgGroupId) (path=$collectionPath) in index: $indexName to Algolia")



    case DropIndex =>
      val batchLogMsgGroupId: BatchLogMsgGroupId = System.nanoTime()

      logger.info(s"Sending remove index (msgId=$batchLogMsgGroupId) for index: $indexName to Algolia")

      val deleteIndex: Future[AlgoliaDeleteIndexResponse] = Future {
        // actually send the data to Algolia!
        Try(algoliaIndex.delete().waitForCompletion()) match {
          case Success(_) =>
            AlgoliaDeleteIndexResponse(batchLogMsgGroupId, None)
          case Failure(t) =>
            AlgoliaDeleteIndexResponse(batchLogMsgGroupId, Some(t))
        }
      }.recover {
        case t: Throwable =>
          AlgoliaDeleteIndexResponse(batchLogMsgGroupId, Some(t))
      }

      // redirect the response of the future back to ourselves
      pipe(deleteIndex).to(self)

      sender ! DroppedIndex(indexName)

    case AlgoliaDeleteIndexResponse(batchLogMsgGroupId, Some(t)) =>
      logger.error(s"Unable to send remove index (msgId=$batchLogMsgGroupId) for index: $indexName to Algolia", t)
      //context.stop(self)    // should we actually stop on error?

    case AlgoliaDeleteIndexResponse(batchLogMsgGroupId, None) =>
      logger.info(s"Sent remove index (msgId=$batchLogMsgGroupId) for index: $indexName to Algolia")
      sender ! DroppedIndex(indexName)
      context.stop(self)
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
