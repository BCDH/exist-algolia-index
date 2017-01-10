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
