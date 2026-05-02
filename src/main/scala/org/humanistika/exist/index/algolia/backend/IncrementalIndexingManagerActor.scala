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

import java.nio.file.Path

import org.humanistika.exist.index.algolia.{CollectionId, CollectionPath, DocumentId, IndexName, IndexableRootObject}
import IncrementalIndexingManagerActor._
import akka.actor.{Actor, ActorRef, Props}
import IndexLocalStoreDocumentActor._
import org.humanistika.exist.index.algolia.AlgoliaIndex.Authentication

object IncrementalIndexingManagerActor {
  val ACTOR_NAME = "IncrementalIndexingManager"

  // various messages
  case class StartDocument(indexName: IndexName, collectionId: CollectionId, documentId: DocumentId)
  case class Add(indexName: IndexName, indexableRootObject: IndexableRootObject)
  case class FinishDocument(indexName: IndexName, userSpecifiedDocumentId: Option[String], collectionId: CollectionId, documentId: DocumentId)
  case class IndexChanges(indexName: IndexName, changes: Changes)
  case class RemoveForDocument(indexName: IndexName, documentId: DocumentId, userSpecifiedDocumentId: Option[String], userSpecifiedVisibleBy: Option[String])
  case class RemoveForCollection(indexName: IndexName, collectionPath: String)
  case class RemovedCollection(indexName: IndexName, collectionPath: String)
  case class AlgoliaRemoveForCollectionSucceeded(indexName: IndexName, collectionPath: String)
  case class AlgoliaRemoveForCollectionFailed(indexName: IndexName, collectionPath: String, error: Throwable)
  case class ConfigureIndex(indexName: IndexName, batchSize: Option[Int])
  case object FlushPendingCollectionRemovals
  case object DropIndexes
}

class IncrementalIndexingManagerActor(dataDir: Path) extends Actor {
  private val indexingStatusStore = IndexingStatusStore(dataDir)
  private val algoliaIndexManagerActor = context.actorOf(Props(classOf[AlgoliaIndexManagerActor], indexingStatusStore), AlgoliaIndexManagerActor.ACTOR_NAME)
  private val indexLocalStoreManagerActor = context.actorOf(Props(classOf[IndexLocalStoreManagerActor], dataDir, indexingStatusStore), IndexLocalStoreManagerActor.ACTOR_NAME)
  private var blockedCollectionsByIndex: Map[IndexName, CollectionPath] = Map.empty
  private var queuedByIndex: Map[IndexName, Vector[Any]] = Map.empty

  override def receive = {

    /* config messages */
    case auth: Authentication =>
      algoliaIndexManagerActor ! auth

    case configureIndex: ConfigureIndex =>
      algoliaIndexManagerActor ! configureIndex


    /* messages for IndexLocalStoreManagerActor */
    case startDocument @ StartDocument(indexName, _, _) =>
      forwardOrQueue(indexName, startDocument, indexLocalStoreManagerActor)

    case add @ Add(indexName, _) =>
      forwardOrQueue(indexName, add, indexLocalStoreManagerActor)

    case finishDocument @ FinishDocument(indexName, _, _, _) =>
      forwardOrQueue(indexName, finishDocument, indexLocalStoreManagerActor)


    /* messages from IndexLocalStoreManagerActor */
    case indexChanges @ IndexChanges(indexName, _) =>
      forwardOrQueue(indexName, indexChanges, algoliaIndexManagerActor)

    case RemovedCollection(indexName, collectionPath) =>
      blockedCollectionsByIndex = blockedCollectionsByIndex - indexName
      replayQueued(indexName)


    /* messages to AlgoliaIndexManagerActor and IndexLocalStoreManagerActor */
    case removeForDocument @ RemoveForDocument(indexName, _, _, _) =>
      if (blockedCollectionsByIndex.contains(indexName)) {
        queuedByIndex = queuedByIndex + (indexName -> (queuedByIndex.getOrElse(indexName, Vector.empty) :+ removeForDocument))
      } else {
        algoliaIndexManagerActor ! removeForDocument
        indexLocalStoreManagerActor ! removeForDocument
      }

    case removeForCollection @ RemoveForCollection(indexName, collectionPath) =>
      indexLocalStoreManagerActor ! removeForCollection

    case AlgoliaRemoveForCollectionSucceeded(indexName, collectionPath) =>
      indexLocalStoreManagerActor ! RemoveForCollection(indexName, collectionPath)

    case AlgoliaRemoveForCollectionFailed(indexName, collectionPath, _) =>
      blockedCollectionsByIndex = blockedCollectionsByIndex - indexName
      replayQueued(indexName)

    case dropIndexes @ DropIndexes =>
      algoliaIndexManagerActor ! dropIndexes
      indexLocalStoreManagerActor ! dropIndexes

    case FlushPendingCollectionRemovals =>
      indexLocalStoreManagerActor ! FlushPendingCollectionRemovals
  }

  private def forwardOrQueue(indexName: IndexName, message: Any, target: ActorRef): Unit = {
    if (blockedCollectionsByIndex.contains(indexName)) {
      queuedByIndex = queuedByIndex + (indexName -> (queuedByIndex.getOrElse(indexName, Vector.empty) :+ message))
    } else {
      target ! message
    }
  }

  private def replayQueued(indexName: IndexName): Unit = {
    val queued = queuedByIndex.getOrElse(indexName, Vector.empty)
    queuedByIndex = queuedByIndex - indexName
    queued.foreach {
      case startDocument @ StartDocument(_, _, _) => self ! startDocument
      case add @ Add(_, _) => self ! add
      case finishDocument @ FinishDocument(_, _, _, _) => self ! finishDocument
      case indexChanges @ IndexChanges(_, _) => self ! indexChanges
      case removeForDocument @ RemoveForDocument(_, _, _, _) => self ! removeForDocument
      case removeForCollection @ RemoveForCollection(_, _) => self ! removeForCollection
      case FlushPendingCollectionRemovals => self ! FlushPendingCollectionRemovals
      case other => self ! other
    }
  }
}
