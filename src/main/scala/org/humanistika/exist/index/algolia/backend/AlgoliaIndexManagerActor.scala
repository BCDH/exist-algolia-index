package org.humanistika.exist.index.algolia.backend

import akka.actor.{Actor, ActorRef, Props}
import com.algolia.search.{APIClient, ApacheAPIClientBuilder, Index}
import org.humanistika.exist.index.algolia.AlgoliaIndex.Authentication
import org.humanistika.exist.index.algolia.{IndexName, IndexableRootObject}
import org.humanistika.exist.index.algolia.IndexableRootObjectJsonSerializer.COLLECTION_PATH_FIELD_NAME
import AlgoliaIndexActor._
import IncrementalIndexingManagerActor.{DropIndexes, IndexChanges}
import com.algolia.search.inputs.batch.{BatchAddObjectOperation, BatchDeleteObjectOperation}
import com.algolia.search.objects.{IndexSettings, Query}
import org.humanistika.exist.index.algolia.AlgoliaIndexWorker.RemoveForCollection
import org.humanistika.exist.index.algolia.backend.IndexLocalStoreDocumentActor.Changes

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
      val newSearchableAttributes = searchableAttributes.find(_.equals(COLLECTION_PATH_FIELD_NAME)).map(_ => searchableAttributes).getOrElse(searchableAttributes :+ COLLECTION_PATH_FIELD_NAME)
      index.setSettings(indexSettings.setSearchableAttributes(newSearchableAttributes.asJava))

      index
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
