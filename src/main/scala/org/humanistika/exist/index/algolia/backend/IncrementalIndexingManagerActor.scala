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

import org.humanistika.exist.index.algolia.{CollectionId, DocumentId, IndexName, IndexableRootObject}
import IncrementalIndexingManagerActor._
import akka.actor.{Actor, Props}
import IndexLocalStoreDocumentActor._
import org.humanistika.exist.index.algolia.AlgoliaIndex.Authentication

object IncrementalIndexingManagerActor {
  val ACTOR_NAME = "IncrementalIndexingManager"

  // various messages
  case class StartDocument(indexName: IndexName, collectionId: CollectionId, documentId: DocumentId)
  case class Add(indexName: IndexName, indexableRootObject: IndexableRootObject)
  case class FinishDocument(indexName: IndexName, userSpecifiedDocumentId: Option[String], collectionId: CollectionId, documentId: DocumentId)
  case class IndexChanges(indexName: IndexName, changes: Changes)
  case class RemoveForDocument(indexName: IndexName, documentId: DocumentId, userSpecifiedDocumentId: Option[String])
  case class RemoveForCollection(indexName: IndexName, collectionPath: String)
  case object DropIndexes
}

class IncrementalIndexingManagerActor(dataDir: Path) extends Actor {
  private val algoliaIndexManagerActor = context.actorOf(Props[AlgoliaIndexManagerActor], AlgoliaIndexManagerActor.ACTOR_NAME)
  private val indexLocalStoreManagerActor = context.actorOf(Props(classOf[IndexLocalStoreManagerActor], dataDir), IndexLocalStoreManagerActor.ACTOR_NAME)

  override def receive = {

    /* config messages */
    case auth: Authentication =>
      algoliaIndexManagerActor ! auth


    /* messages for IndexLocalStoreManagerActor */
    case startDocument: StartDocument =>
      indexLocalStoreManagerActor ! startDocument

    case add: Add =>
      indexLocalStoreManagerActor ! add

    case finishDocument: FinishDocument =>
      indexLocalStoreManagerActor ! finishDocument


    /* messages from IndexLocalStoreManagerActor */
    case indexChanges : IndexChanges =>
      algoliaIndexManagerActor ! indexChanges


    /* messages to AlgoliaIndexManagerActor and IndexLocalStoreManagerActor */
    case removeForDocument : RemoveForDocument =>
      algoliaIndexManagerActor ! removeForDocument
      indexLocalStoreManagerActor ! removeForDocument

    case removeForCollection : RemoveForCollection =>
      algoliaIndexManagerActor ! removeForCollection
      indexLocalStoreManagerActor ! removeForCollection

    case dropIndexes @ DropIndexes =>
      algoliaIndexManagerActor ! dropIndexes
      indexLocalStoreManagerActor ! dropIndexes
  }
}
