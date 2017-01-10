package org.humanistika.exist.index.algolia.backend

import akka.actor.{Actor, ActorRef, Props}
import com.algolia.search.{APIClient, ApacheAPIClientBuilder, Index}
import org.humanistika.exist.index.algolia.AlgoliaIndex.Authentication
import org.humanistika.exist.index.algolia.{IndexName, IndexableRootObject}
import org.humanistika.exist.index.algolia.IndexableRootObjectJsonSerializer.{COLLECTION_PATH_FIELD_NAME, DOCUMENT_ID_FIELD_NAME}
import AlgoliaIndexActor._
import IncrementalIndexingManagerActor.{DropIndexes, IndexChanges, RemoveForCollection, RemoveForDocument}
import com.algolia.search.inputs.batch.{BatchAddObjectOperation, BatchDeleteObjectOperation}
import com.algolia.search.objects.{IndexSettings, Query}
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
}

class AlgoliaIndexActor(indexName: IndexName, algoliaIndex: Index[IndexableRootObject]) extends Actor {
  override def receive: Receive = {

    case Changes(_, additions, removals) =>
      val batchOperations = additions.map(new BatchAddObjectOperation(_)) ++ removals.map(new BatchDeleteObjectOperation(_))
      algoliaIndex.batch(batchOperations.asJava).waitForCompletion()

    case RemoveForDocument(_, documentId, userSpecifiedDocumentId) =>
      val query = new Query()
      query.setRestrictSearchableAttributes(DOCUMENT_ID_FIELD_NAME)
      query.setQuery(userSpecifiedDocumentId.getOrElse(documentId.toString))
      algoliaIndex.deleteByQuery(query)

    case RemoveForCollection(_, collectionPath) =>
      val query = new Query()
      query.setRestrictSearchableAttributes(COLLECTION_PATH_FIELD_NAME)
      query.setQuery(collectionPath)
      algoliaIndex.deleteByQuery(query)

    case DropIndex =>
      algoliaIndex.delete().waitForCompletion()
      sender ! DroppedIndex(indexName)
      context.stop(self)
  }
}
