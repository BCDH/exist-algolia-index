package org.humanistika.exist.index.algolia.backend

import java.io.StringWriter
import java.nio.file.{Files, Path}
import java.util.Optional
import java.util.stream.Collectors

import akka.actor.{Actor, ActorRef, Props}
import akka.pattern.gracefulStop

import scala.concurrent.duration._
import com.fasterxml.jackson.databind.ObjectMapper
import gnu.crypto.hash.RipeMD160
import org.humanistika.exist.index.algolia._
import IndexLocalStoreDocumentActor._
import IncrementalIndexingManagerActor._
import org.exist.util.FileUtils
import org.humanistika.exist.index.algolia.AlgoliaIndexWorker.RemoveForCollection
import org.humanistika.exist.index.algolia.IndexableRootObjectJsonSerializer.COLLECTION_PATH_FIELD_NAME
import resource._

import scala.collection.JavaConverters._
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success, Try}

object IndexLocalStoreManagerActor {
  val ACTOR_NAME = "IndexLocalStoreManager"
}

class IndexLocalStoreManagerActor(dataDir: Path) extends Actor {
  private val indexesDir = dataDir.resolve("algolia-index").resolve("indexes")
  private var perIndexLocalStoreActors: Map[IndexName, ActorRef] = Map.empty

  override def preStart() {
    if(!Files.exists(indexesDir)) {
      Files.createDirectories(indexesDir)
    }
    super.preStart()
  }

  override def receive: Receive = {
    case startDocument @ StartDocument(indexName, _, _) =>
      val indexActor = getOrCreatePerIndexActor(indexName)
      indexActor ! startDocument

    case add @ Add(indexName, _, _) =>
      val indexActor = perIndexLocalStoreActors(indexName)
      indexActor ! add

    case finishDocument @ FinishDocument(indexName, _, _, _) =>
      val indexActor = perIndexLocalStoreActors(indexName)
      indexActor ! finishDocument

    case indexChanges : IndexChanges =>
      context.parent ! indexChanges

    case removeForCollection @ RemoveForCollection(indexName, _) =>
      val indexActor = getOrCreatePerIndexActor(indexName)
      indexActor ! removeForCollection

    case DropIndexes =>
      for((indexName, indexActor) <- perIndexLocalStoreActors) {
        context.stop(indexActor)
        FileUtils.delete(indexesDir.resolve(indexName))
        this.perIndexLocalStoreActors = perIndexLocalStoreActors - indexName
      }
  }

  private def getOrCreatePerIndexActor(indexName: String) : ActorRef = perIndexLocalStoreActors.getOrElse(indexName, createPerIndexLocalStoreActor(indexName))

  private def createPerIndexLocalStoreActor(indexName: String): ActorRef = {
    val perIndexActor = context.actorOf(Props(classOf[IndexLocalStoreActor], indexesDir, indexName), indexName)
    perIndexLocalStoreActors = perIndexLocalStoreActors + (indexName -> perIndexActor)
    perIndexActor
  }
}

class IndexLocalStoreActor(indexesDir: Path, indexName: String) extends Actor {
  private val localIndexStoreDir = indexesDir.resolve(indexName)
  private var processing: Map[DocumentId, Timestamp] = Map.empty
  private var perDocumentActors: Map[DocumentId, ActorRef] = Map.empty

  override def preStart() {
    if(!Files.exists(localIndexStoreDir)) {
      Files.createDirectories(localIndexStoreDir)
    }
    super.preStart()
  }

  override def receive: Receive = {
    case StartDocument(_, _, documentId) =>
      val timestamp = System.currentTimeMillis
      this.processing = processing + (documentId -> timestamp)
      getOrCreatePerDocumentActor(documentId)

    case Add(_, userSpecifiedDocumentId, iro @ IndexableRootObject(_, _, documentId, _, _, _)) =>
      val perDocumentActor = getOrCreatePerDocumentActor(documentId)
      val timestamp = processing(documentId)
      perDocumentActor ! Write(timestamp, userSpecifiedDocumentId, iro)

    case FinishDocument(_, userSpecifiedDocumentId, _, documentId) =>
      val perDocumentActor = perDocumentActors(documentId)
      val timestamp = processing(documentId)
      perDocumentActor ! FindChanges(timestamp, userSpecifiedDocumentId, documentId)

    case changes @ Changes(documentId, _, _) =>
      // cleanup per document actor (no longer required)
      val perDocumentActor = perDocumentActors(documentId)
      this.processing = processing - documentId
      context.stop(perDocumentActor)
      perDocumentActors = perDocumentActors - documentId

      // tell the IndexLocalStoreManagerActor that there are changes to index
      context.parent ! IndexChanges(indexName, changes)
    //TODO(AR) when to delete previous timestamp (after upload into Algolia)

    case RemoveForCollection(_, collectionPath) =>
      import context.dispatcher
      //stop the perDocumentActors, we want exclusive access
      val stopped: Iterable[Future[(DocumentId, Boolean)]] =
        for((documentId, perDocumentActor) <- perDocumentActors)
          yield gracefulStop(perDocumentActor, 2 minutes).map((documentId, _))

      Try(Await.result(Future.sequence(stopped), 5 minutes)) match {
        case Success(stoppedPerDocumentActors) if !stoppedPerDocumentActors.exists(!_._2) =>
          // all perDocuemntActors were gracefully stopped
          this.perDocumentActors = Map.empty

          // find the latest timestamp dir for each document id
          val latestTimestampDirs: Seq[Path] = managed(Files.list(localIndexStoreDir)).map { indexDirStream =>
            indexDirStream
              .filter(Files.isDirectory(_))
              .collect(Collectors.toList())
              .asScala
              .map(getLatestTimestampDir(_, None))
          }.tried match {
            case Success(timestampDirs) => timestampDirs.flatten
            case Failure(t) => throw t //TODO(AR) better error messages
          }

          //delete any rootObjects from the latest timestamps which match the collection path tree
          val rootObjectsInCollectionTree = latestTimestampDirs.map(rootObjectsByCollectionTree(_, collectionPath)).flatten
          for(rootObjectInCollectionTree <- rootObjectsInCollectionTree) {
            FileUtils.deleteQuietly(rootObjectInCollectionTree)
          }

          // cleanup any empty timestamp dirs
          for(latestTimestampDir <- latestTimestampDirs if isEmpty(latestTimestampDir)) {
            FileUtils.deleteQuietly(latestTimestampDir)
          }

          // cleanup any empty document dirs
          for(documentDir <- latestTimestampDirs.map(_.getParent) if isEmpty(documentDir)) {
            FileUtils.deleteQuietly(documentDir)
          }

        case Success(stoppedPerDocumentActors) if stoppedPerDocumentActors.exists(!_._2) =>
          // not all perDocumentActors were gracefully stopped
          this.perDocumentActors = Map.empty
          val failedDocumentIds = stoppedPerDocumentActors.filterNot(_._2).map(_._1)
          throw new IllegalStateException(s"Could not stop document actors for ${failedDocumentIds}")

        case Failure(t) =>
          throw t
      }
  }

  private def isEmpty(dir: Path) : Boolean = {
    managed(Files.list(dir)).map { stream =>
      !stream.findFirst().isPresent
    }.tried match {
      case Success(result) => result
      case Failure(t) => throw t //TODO(AR) better error messsages
    }
  }

  /**
    * Gets the paths of all serialized IndeableRootObjects which are in the
    * collection or (sub-collection of) the collectionPath
    */
  private def rootObjectsByCollectionTree(timestampDir: Path, collectionPath: CollectionPath): Seq[Path] = {
    def matchesCollectionPathRoot(rootObjectPath: Path): Boolean = {
      val objectMapper = new ObjectMapper()
      val tree = objectMapper.readTree(rootObjectPath.toFile)
      val rootObjectCollectionPath = Option(tree.get(COLLECTION_PATH_FIELD_NAME)).flatMap(node => Option(node.asText))
      rootObjectCollectionPath.exists(_.startsWith(collectionPath))
    }

    managed(Files.list(timestampDir)).map { timestampDirStream =>
      timestampDirStream
        .filter(Files.isRegularFile(_))
        .filter(FileUtils.fileName(_).endsWith(".json"))
        .filter(matchesCollectionPathRoot)
        .collect(Collectors.toList())
        .asScala
    }.tried match {
      case Success(rootObjectsMatchingCollection) =>
        rootObjectsMatchingCollection
      case Failure(t) => throw t //TODO(AR) better error messages
    }
  }

  private def getOrCreatePerDocumentActor(documentId: DocumentId) : ActorRef = perDocumentActors.getOrElse(documentId, createPerDocumentActor(documentId))

  private def createPerDocumentActor(documentId: DocumentId): ActorRef = {
    val perDocumentActor = context.actorOf(Props(classOf[IndexLocalStoreDocumentActor], localIndexStoreDir, documentId), s"doc-$documentId")
    perDocumentActors = perDocumentActors + (documentId -> perDocumentActor)
    perDocumentActor
  }
}

object IndexLocalStoreDocumentActor {
  val mapper = new ObjectMapper
  case class Write(timestamp: Timestamp, userSpecifiedDocumentId: Option[String], indexableRootObject: IndexableRootObject)
  case class FindChanges(timestamp: Timestamp, userSpecifiedDocumentId: Option[String], documentId: DocumentId)
  type objectID = String
  case class Changes(documentId: DocumentId, additions: Seq[LocalIndexableRootObject], deletions: Seq[objectID])

  /**
    * Finds the latest timestamp dir inside the given dir
    *
    * @param dir The dir to search for timestamp dirs
    * @param lt Optionally a timestamp that the latest timestamp dir must be less than
    */
  def getLatestTimestampDir(dir: Path, lt: Option[Timestamp] = None): Option[Path] = {
    def timestampFromPath(p: Path): Timestamp = p.getFileName.toString.toLong

    managed(Files.list(dir)).map{ stream =>
      stream
        .filter(Files.isDirectory(_))
        .filter(dir => lt.map(timestamp => timestampFromPath(dir) < timestamp).getOrElse(true))
        .collect(Collectors.toList()).asScala
    }.tried match {
      case Success(prevTimestamps) =>
        prevTimestamps
          .sortWith{ case (p1, p2) => timestampFromPath(p1) > timestampFromPath(p2)}
          .headOption

      case Failure(t) => throw t //TODO(AR) better error reporting
    }
  }
}

class IndexLocalStoreDocumentActor(indexDir: Path, documentId: DocumentId) extends Actor {
  private val ripeMd160 = new RipeMD160

  override def receive: Receive = {
    case Write(timestamp, userSpecifiedDocumentId, indexableRootObject) =>
      val usableDocId = usableDocumentId(userSpecifiedDocumentId, indexableRootObject.documentId)
      val dir = getDir(usableDocId, timestamp)
      if (!Files.exists(dir)) {
        Files.createDirectories(dir)
      }

      val usableNodeId = indexableRootObject.userSpecifiedNodeId
        .getOrElse(indexableRootObject.nodeId
          .getOrElse(DOCUMENT_NODE_ID))

      managed(Files.newBufferedWriter(dir.resolve(s"${usableNodeId}.json"))).map{ writer =>
        writer.write(serializeJson(indexableRootObject))
      }.tried match {
        case Success(_) =>
        case Failure(t) => throw t    //TODO(AR) do some better error handling
      }

    case FindChanges(timestamp, userSpecifiedDocumentId, documentId) =>
      val usableDocId = usableDocumentId(userSpecifiedDocumentId, documentId)
      val dir = getDir(usableDocId, timestamp)
      val prevDir = findPreviousDir(dir)

      prevDir match {
        case Some(prev) =>
          //TODO(AR) need to find additions and deletions and send them back to the sender
          throw new UnsupportedOperationException("TODO(AR) need to implement this")

        case None =>
          managed(Files.list(dir)).map{ stream =>
            stream
              .filter(Files.isRegularFile(_))
              .collect(Collectors.toList()).asScala
          }.tried match {
            case Success(uploadable) =>
              sender ! Changes(documentId, uploadable.map(LocalIndexableRootObject(_)), Seq.empty)

            case Failure(t) => throw t  //TODO(AR) do some better error handling
          }
      }
  }

  private def findPreviousDir(timestampDir: Path): Option[Path] = {
    val timestamp = timestampDir.getFileName.toString.toLong
    val docDir = timestampDir.getParent
    getLatestTimestampDir(docDir, Some(timestamp))
  }

  private def getDir(usableDocumentId: String, timestamp: Long) = indexDir.resolve(usableDocumentId).resolve(timestamp.toString)

  private def usableDocumentId(userSpecifiedDocumentId: Option[String], documentId: Int): String = userSpecifiedDocumentId.getOrElse(documentId.toString)

  private def serializeJson(indexableRootObject: IndexableRootObject): String = {
    managed(new StringWriter).map { writer =>
      mapper.writeValue(writer, indexableRootObject)
      writer.toString
    }.tried match {
      case Success(s) =>
        s
      case Failure(t) =>
        throw t
    }
  }

  //val checksum = encodeHexString(hash())
  //
  //  private def hash(s: String): Array[Byte] = {
  //    val bs = s.getBytes
  //    ripeMd160.update(bs, 0, bs.length)
  //    ripeMd160.digest()
  //  }
}

/***
-- algolia-index
  - indexes
    - my-index
      - document-id
        - timestamp
  ***/
