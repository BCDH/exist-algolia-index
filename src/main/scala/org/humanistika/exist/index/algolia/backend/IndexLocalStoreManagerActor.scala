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

import java.io.StringWriter
import java.nio.file.{Files, Path}
import java.util.stream.Collectors

import akka.actor.{Actor, ActorRef, Props}
import akka.pattern.gracefulStop

import scala.concurrent.duration._
import com.fasterxml.jackson.databind.ObjectMapper
import org.humanistika.exist.index.algolia._
import IndexLocalStoreDocumentActor._
import IncrementalIndexingManagerActor._
import org.exist.util.FileUtils
import org.humanistika.exist.index.algolia.IndexableRootObjectJsonSerializer.{COLLECTION_PATH_FIELD_NAME, OBJECT_ID_FIELD_NAME}
import org.humanistika.exist.index.algolia.backend.IndexLocalStoreActor.FILE_SUFFIX

import scala.collection.JavaConverters._
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success, Try}
import cats.effect.{IO, Resource}
import cats.effect.unsafe.implicits.global    // TODO(AR) switch to using cats.effect.IOApp
import cats.syntax.either._
import grizzled.slf4j.Logger
import org.apache.commons.codec.binary.Base32

object IndexLocalStoreManagerActor {
  val ACTOR_NAME = "IndexLocalStoreManager"
}

/**
  * Writes copies of the JSON objects that are going to be sent to Algolia
  * to the filesystem in the following folder hierarchy
  *
  *   - algolia-index
  *     |
  *     | - indexes
  *         |
  *         | - my-index
  *             |
  *             | - document-id (base32 encoded, if user specified)
  *                 |
  *                 | - timestamp
  */
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

    case add @ Add(indexName, _) =>
      val indexActor = perIndexLocalStoreActors(indexName)
      indexActor ! add

    case finishDocument @ FinishDocument(indexName, _, _, _) =>
      val indexActor = perIndexLocalStoreActors(indexName)
      indexActor ! finishDocument

    case indexChanges : IndexChanges =>
      context.parent ! indexChanges

    case removeForDocument @ RemoveForDocument(indexName, _, _) =>
      val indexActor = getOrCreatePerIndexActor(indexName)
      indexActor ! removeForDocument

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

object IndexLocalStoreActor {
  val FILE_SUFFIX = "json"
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

    case Add(_, iro @ IndexableRootObject(_, _, documentId, _, _, _, _)) =>
      val perDocumentActor = getOrCreatePerDocumentActor(documentId)
      val timestamp = processing(documentId)
      perDocumentActor ! Write(timestamp, iro)

    case FinishDocument(_, userSpecifiedDocumentId, _, documentId) =>
      val perDocumentActor = perDocumentActors(documentId)
      val timestamp = processing(documentId)
      perDocumentActor ! FindChanges(timestamp, userSpecifiedDocumentId, documentId)

    case changes @ Changes(documentId, _, _, _) =>
      // cleanup per document actor (no longer required)
      val perDocumentActor = perDocumentActors(documentId)
      this.processing = processing - documentId
      context.stop(perDocumentActor)
      this.perDocumentActors = perDocumentActors - documentId

      // tell the IndexLocalStoreManagerActor that there are changes to index
      context.parent ! IndexChanges(indexName, changes)
    //TODO(AR) when to delete previous timestamp (after upload into Algolia)

    case RemoveForDocument(_, documentId, userSpecifiedDocumentId) =>
      val perDocumentActor = getOrCreatePerDocumentActor(documentId)
      val maybeTimestamp = processing.get(documentId)
      perDocumentActor ! RemoveDocument(documentId, userSpecifiedDocumentId, maybeTimestamp)  // perDocumentActor will stop itself!

    case RemovedDocument(documentId) =>
      val perDocumentActor = perDocumentActors(documentId)
      this.processing = processing - documentId
      context.stop(perDocumentActor)
      this.perDocumentActors = perDocumentActors - documentId  //perDocumentActor is no longer required

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
          val fileIO = Resource.fromAutoCloseable(IO { Files.list(localIndexStoreDir)}).use { indexDirStream =>
            IO {
              indexDirStream
                .filter(Files.isDirectory(_))
                .collect(Collectors.toList())
                .asScala
                .map(getLatestTimestampDir(_, None))
            }
          }
          val latestTimestampDirs: Seq[Path] = fileIO
            .redeem(_.asLeft, _.asRight)
            .unsafeRunSync() match {
            case Right(timestampDirs) =>
              timestampDirs.flatten
            case Left(t) =>
              throw t //TODO(AR) better error messages
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
          throw new IllegalStateException(s"Could not stop document actors for ${failedDocumentIds}") //TODO(AR) better error messages

        case Failure(t) =>
          throw t //TODO(AR) better error messages
      }
  }

  private def isEmpty(dir: Path) : Boolean = {
    val fileIO = Resource.fromAutoCloseable(IO { Files.list(dir)}).use { stream =>
      IO {
        !stream.findFirst().isPresent
      }
    }

    fileIO
        .redeem(_.asLeft, _.asRight)
        .unsafeRunSync() match {
      case Right(result) =>
        result
      case Left(t) =>
        throw t //TODO(AR) better error messsages
    }
  }

  /**
    * Gets the paths of all serialized IndexableRootObjects which are in the
    * collection or (sub-collection of) the collectionPath
    */
  private def rootObjectsByCollectionTree(timestampDir: Path, collectionPath: CollectionPath): Seq[Path] = {
    def matchesCollectionPathRoot(rootObjectPath: Path): Boolean = {
      val objectMapper = new ObjectMapper()
      val tree = objectMapper.readTree(rootObjectPath.toFile)
      val rootObjectCollectionPath = Option(tree.get(COLLECTION_PATH_FIELD_NAME)).flatMap(node => Option(node.asText))
      rootObjectCollectionPath.exists(_.startsWith(collectionPath))
    }

    val fileIO = Resource.fromAutoCloseable(IO { Files.list(timestampDir)}).use { timestampDirStream =>
      IO {
        timestampDirStream
          .filter(Files.isRegularFile(_))
          .filter(FileUtils.fileName(_).endsWith(s".$FILE_SUFFIX"))
          .filter(matchesCollectionPathRoot)
          .collect(Collectors.toList())
          .asScala
      }
    }

    fileIO
      .redeem(_.asLeft, _.asRight)
      .unsafeRunSync() match {
      case Right(rootObjectsMatchingCollection) =>
        rootObjectsMatchingCollection
      case Left(t) => throw t //TODO(AR) better error messages
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
  case class Write(timestamp: Timestamp, indexableRootObject: IndexableRootObject)
  case class FindChanges(timestamp: Timestamp, userSpecifiedDocumentId: Option[String], documentId: DocumentId)
  case class Changes(documentId: DocumentId, additions: Seq[LocalIndexableRootObject], updates: Seq[LocalIndexableRootObject], deletions: Seq[objectID])
  case class RemoveDocument(documentId: DocumentId, userSpecifiedDocumnentId: Option[String], maybeTimestamp: Option[Timestamp])
  case class RemovedDocument(documentId: DocumentId)

  /**
    * Finds the latest timestamp dir inside the given dir
    *
    * @param dir The dir to search for timestamp dirs
    * @param lt Optionally a timestamp that the latest timestamp dir must be less than
    */
  def getLatestTimestampDir(dir: Path, lt: Option[Timestamp] = None): Option[Path] = {
    def timestampFromPath(p: Path): Timestamp = p.getFileName.toString.toLong

    val fileIO = Resource.fromAutoCloseable(IO { Files.list(dir)}).use { stream =>
      IO {
        stream
          .filter(Files.isDirectory(_))
          .filter(dir => lt.map(timestamp => timestampFromPath(dir) < timestamp).getOrElse(true))
          .collect(Collectors.toList()).asScala
      }
    }

    fileIO
      .redeem(_.asLeft, _.asRight)
      .unsafeRunSync() match {
      case Right(prevTimestamps) =>
        prevTimestamps
          .sortWith{ case (p1, p2) => timestampFromPath(p1) > timestampFromPath(p2)}
          .headOption

      case Left(t) =>
        throw t  //TODO(AR) better error reporting
    }
  }
}

/**
  * Note when using userSpecifiedDocumentIds or userSpecifiedNodeIds
  * the ids will be base32 encoded to ensure that the filenames
  * are correct on case-insensitive filesystems such as Mac HFS+
  */
class IndexLocalStoreDocumentActor(indexDir: Path, documentId: DocumentId) extends Actor {
  private lazy val logger = Logger(classOf[IndexLocalStoreDocumentActor])

  override def receive: Receive = {
    case Write(timestamp, indexableRootObject) =>
      val documentDirName = filenameUsableDocumentId(indexableRootObject.userSpecifiedDocumentId, indexableRootObject.documentId)
      val dir = getTimestampDir(documentDirName, timestamp)
      if (!Files.exists(dir)) {
        Files.createDirectories(dir)
      }

      val nodeIdFilename = filenameUsableNodeId(indexableRootObject.userSpecifiedNodeId, indexableRootObject.nodeId)
      val file = dir.resolve(s"${nodeIdFilename}.$FILE_SUFFIX")

      Resource.fromAutoCloseable(IO {Files.newBufferedWriter(file)}).use { writer =>
        IO {
          writer.write(serializeJson(indexableRootObject))
        }
      }.redeem(_.asLeft, _.asRight).unsafeRunSync() match {
        case Right(_) =>
          if(logger.isTraceEnabled) {
            logger.trace(s"Stored JSON rootObject '${file}' for (collectionPath=${indexableRootObject.collectionPath}, docId=${indexableRootObject.documentId}, userSpecificDocId=${indexableRootObject.userSpecifiedDocumentId}, nodeId=${indexableRootObject.nodeId}, userSpecificNodeId=${indexableRootObject.userSpecifiedNodeId}): ${indexDir.getFileName}")
          }
        case Left(t) => throw t    //TODO(AR) do some better error handling
      }

    case FindChanges(timestamp, userSpecifiedDocumentId, documentId) =>
      val documentDirName = filenameUsableDocumentId(userSpecifiedDocumentId, documentId)
      val dir = getTimestampDir(documentDirName, timestamp)
      val prevDir = findPreviousDir(dir)

      prevDir match {
        case Some(prev) =>
          // compares the previous version with this version and sends the changes
          diff(prev, dir) match {
            case Right((additions, updates, deletions)) if(additions.nonEmpty || updates.nonEmpty || deletions.nonEmpty) =>
              sender ! Changes(documentId, additions, updates, deletions)

            case Right((additions, updates, deletions)) if(additions.isEmpty && updates.isEmpty && deletions.isEmpty) =>
              if(logger.isTraceEnabled) {
                logger.trace(s"No changes found between: ${prev.toAbsolutePath.toString} and ${dir.toAbsolutePath.toString}")
              }

            case Left(ts) =>
              throw ts.head  //TODO(AR) do some better error handling
          }

        case None =>
          // no previous version, so everything is an addition
          listFiles(dir) match {
            case Right(uploadable) =>
              sender ! Changes(documentId, uploadable.map(LocalIndexableRootObject(_)), Seq.empty, Seq.empty)

            case Left(ts) =>
              throw ts.head  //TODO(AR) do some better error handling
          }
      }

    case RemoveDocument(documentId, userSpecifiedDocumentId, maybeTimestamp) =>
      val documentDirName = filenameUsableDocumentId(userSpecifiedDocumentId, documentId)
      val maybeDocTimestampDir = maybeTimestamp.map(getTimestampDir(documentDirName, _)).orElse(getLatestTimestampDir(getDocDir(documentDirName), None))

      maybeDocTimestampDir match {
        case Some(docTimestampDir) =>
          // we now have the latest timestamp dir for the documentId
          if(FileUtils.deleteQuietly(docTimestampDir)) {
            if (logger.isTraceEnabled) {
              logger.trace(s"Removed JSON rootObjects '${docTimestampDir}' for (docId=${documentId}, userSpecificDocId=${userSpecifiedDocumentId}): ${indexDir.getFileName}")
            }
            context.parent ! RemovedDocument(documentId)
          } else {
            throw new IllegalStateException(s"Unable to remove for document (docId=${documentId}, userSpecificDocId=${userSpecifiedDocumentId}) at timestamp: $maybeTimestamp, path: $docTimestampDir")
          }

        case None =>
          throw new IllegalStateException(s"Unable to find doc timestamp dir to remove for document (docId=${documentId}, userSpecificDocId=${userSpecifiedDocumentId}) at timestamp: $maybeTimestamp")
      }


  }


  type Addition = LocalIndexableRootObject
  type Update = LocalIndexableRootObject
  type Removal = objectID

  /**
   * The diff is calculated as follows:
   *
   * 1) If a file exists in prev but not current, then it is a removal
   *
   * 2) If a file exists in prev and current, but the checksums vary then current replaces prev
   *
   * 4) if a file exists in current but not prev, then it is an addition
   */
  private def diff(prev: Path, current: Path) : Either[Seq[Throwable], (Seq[Addition], Seq[Update], Seq[Removal])] = {

    def removalsOrUpdates() : Either[Seq[Throwable], Seq[Either[Removal, Update]]] = listFiles(prev).map(_.map(removalOrUpdate).flatten)

    def removalOrUpdate(prevFile: Path): Option[Either[Removal, Update]] = {
      val currentFile = current.resolve(prevFile.getFileName)
      if(Files.exists(currentFile)) {
        val prevChecksum = checksum(prevFile)
        val currentChecksum = checksum(currentFile)
        if(prevChecksum != currentChecksum) {
          // update
          Some(LocalIndexableRootObject(currentFile).asRight)
        } else {
          // no change
          None
        }
      } else {
        // removal
        val prevObjectId = readObjectId(prevFile, mapper)
        prevObjectId.map(_.asLeft)
      }
    }

    def additions(): Either[Seq[Throwable], Seq[Addition]] = {
      listFiles(current)
        .map(_.filter(currentFile => !Files.exists(prev.resolve(currentFile.getFileName))))
        .map(_.map(LocalIndexableRootObject(_)))
    }

    def split[L, R](lrs: Seq[Either[L, R]]) : (Seq[L], Seq[R]) = {
      lrs.foldLeft((Seq.empty[L], Seq.empty[R])) { (leftsRights, lr) =>
        val (lefts, rights) = leftsRights
        lr match {
          case Left(l) => (lefts :+ l, rights)
          case Right(r) => (lefts, rights :+ r)
        }
      }
    }

    if(logger.isTraceEnabled) {
      logger.trace(s"Performing diff between: prev='$prev' and current='$current'")
    }

    removalsOrUpdates()
      .flatMap(rou => additions().map { adds =>
        val (rems, upds) = split(rou)
        (adds, upds, rems)
      })
  }

  private def listFiles(dir: Path) : Either[Seq[Throwable], Seq[Path]] = {
    Resource.fromAutoCloseable(IO { Files.list(dir)}).use { stream =>
      IO {
        stream
          .filter(Files.isRegularFile(_))
          .collect(Collectors.toList()).asScala
      }
    }.redeem(_.asLeft.leftMap(Seq(_)), _.asRight)
      .unsafeRunSync()
  }

  private def findPreviousDir(timestampDir: Path): Option[Path] = {
    val timestamp = timestampDir.getFileName.toString.toLong
    val docDir = timestampDir.getParent
    getLatestTimestampDir(docDir, Some(timestamp))
  }

  private def getDocDir(documentDirName: String) = indexDir.resolve(documentDirName)

  private def getTimestampDir(documentDirName: String, timestamp: Long) = getDocDir(documentDirName).resolve(timestamp.toString)

  /**
    * Gets a documnet id
    */
  private def filenameUsableDocumentId(userSpecifiedDocumentId: Option[String], documentId: Int): String = userSpecifiedDocumentId.map(base32Encode)getOrElse(documentId.toString)

  private def filenameUsableNodeId(userSpecifiedNodeId: Option[String], nodeId: Option[String]) = userSpecifiedNodeId.map(base32Encode).getOrElse(nodeId.getOrElse(DOCUMENT_NODE_ID))

  private def serializeJson(indexableRootObject: IndexableRootObject): String = {
    val serializeIO = Resource.fromAutoCloseable(IO { new StringWriter()}).use { writer =>
      IO {
        mapper.writeValue(writer, indexableRootObject)
        writer.toString
      }
    }

    serializeIO
      .redeem(_.asLeft, _.asRight)
      .unsafeRunSync() match {
      case  Right(result) =>
        result
      case Left(t) =>
        throw t
    }
  }

  private def checksum(file: Path): Either[Throwable, Array[Byte]] = Checksum.checksum(file, Checksum.MD5)

  private def base32Encode(plain: String): String = {
    val base32 = new Base32()
    base32.encodeAsString(plain.getBytes(java.nio.charset.StandardCharsets.UTF_8))
  }
}
