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
import com.algolia.search.{APIClient, ApacheAPIClientBuilder, Index}
import org.humanistika.exist.index.algolia.readObjectId
import org.humanistika.exist.index.algolia.AlgoliaIndex.Authentication
import org.humanistika.exist.index.algolia.{IndexName, IndexableRootObject, LocalIndexableRootObject}
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

object AlgoliaIndexManagerActor {
  val ACTOR_NAME = "AlgoliaIndexManager"
}

class AlgoliaIndexManagerActor extends Actor {
  private var client: Option[APIClient] = None
  private var perIndexActors: Map[IndexName, ActorRef] = Map.empty

  override def receive: Receive = {
    case auth: Authentication =>
      this.client = Some(new ApacheAPIClientBuilder(auth.applicationId, auth.adminApiKey).build())

    case IndexChanges(indexName, changes) =>
      val indexActor = getOrCreatePerIndexActor(indexName)
      indexActor ! changes

    case rfd @ RemoveForDocument(indexName, _, _) =>
      val indexActor = getOrCreatePerIndexActor(indexName)
      indexActor ! rfd

    case rfc @ RemoveForCollection(indexName, _) =>
      val indexActor = getOrCreatePerIndexActor(indexName)
      indexActor ! rfc

    case DropIndexes =>
      for(indexName <- getClientOrThrow.listIndices.asScala.map(_.getName)) {
        val indexActor = getOrCreatePerIndexActor(indexName)
        indexActor ! DropIndex
      }

    case DroppedIndex(indexName: IndexName) =>
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
  private lazy val mapper = new ObjectMapper
}

class AlgoliaIndexActor(indexName: IndexName, algoliaIndex: Index[IndexableRootObject]) extends Actor {
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
      algoliaIndex.batch(batchOperations.asJava).waitForCompletion()

    case RemoveForDocument(_, documentId, userSpecifiedDocumentId) =>
      if(logger.isTraceEnabled) {
        logger.trace(s"Removing document (id=$documentId, userSpecificDocId=$userSpecifiedDocumentId) from index: $indexName")
      }
      val query = new Query()
      query.setRestrictSearchableAttributes(DOCUMENT_ID_FIELD_NAME)
      query.setQuery(userSpecifiedDocumentId.getOrElse(documentId.toString))
      algoliaIndex.deleteByQuery(query)

    case RemoveForCollection(_, collectionPath) =>
      if(logger.isTraceEnabled) {
        logger.trace(s"Removing documents for Collection (path=$collectionPath) from index: $indexName")
      }
      val query = new Query()
      query.setRestrictSearchableAttributes(COLLECTION_PATH_FIELD_NAME)
      query.setQuery(collectionPath)
      algoliaIndex.deleteByQuery(query)

    case DropIndex =>
      if(logger.isTraceEnabled) {
        logger.trace(s"Removing index: $indexName")
      }
      algoliaIndex.delete().waitForCompletion()
      sender ! DroppedIndex(indexName)
      context.stop(self)
  }

  private def logChanges(changes: Changes) {
    for(addition <- changes.additions) {
      logger.trace(s"Adding object(id=${readObjectId(addition.path, mapper)} path=${addition.path}) to index: $indexName")
    }

    for(update <- changes.updates) {
      logger.trace(s"Updating object(id=${readObjectId(update.path, mapper)} path=${update.path}) to index: $indexName")
    }

    for(removal <- changes.deletions) {
      logger.trace(s"Removing object(id=${removal}) from index: $indexName")
    }
  }
}
