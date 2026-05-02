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

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardCopyOption}
import java.time.Instant

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.{ArrayNode, ObjectNode}
import org.humanistika.exist.index.algolia.{CollectionPath, IndexName}

object IndexingStatusStore {
  val CURRENT = "current"
  val DEGRADED = "degraded"
  val STALE_LOCAL_STORE = "stale_local_store"

  val BATCH_WRITE = "batch_write"
  val DOCUMENT_DELETE = "document_delete"
  val COLLECTION_DELETE = "collection_delete"
  val INDEX_DROP = "index_drop"

  private val mapper = new ObjectMapper()

  val noop: IndexingStatusStore = new IndexingStatusStore(None)

  def apply(dataDir: Path): IndexingStatusStore =
    new IndexingStatusStore(Some(dataDir.resolve("algolia-index").resolve("status.json")))
}

class IndexingStatusStore private (maybeStatusFile: Option[Path]) {
  import IndexingStatusStore._

  private val records: scala.collection.mutable.LinkedHashMap[String, ObjectNode] =
    scala.collection.mutable.LinkedHashMap.empty

  maybeStatusFile.foreach(loadExisting)

  def markCurrent(indexName: IndexName, collectionPath: Option[CollectionPath], operation: String, objectCount: Option[Int] = None): Unit =
    record(indexName, collectionPath, operation, CURRENT, objectCount, None)

  def markDegraded(indexName: IndexName, collectionPath: Option[CollectionPath], operation: String, error: Throwable): Unit =
    record(indexName, collectionPath, operation, DEGRADED, None, Some(error))

  def markStaleLocalStore(indexName: IndexName, collectionPath: CollectionPath, operation: String): Unit =
    record(indexName, Some(collectionPath), operation, STALE_LOCAL_STORE, None, None)

  private def record(indexName: IndexName, collectionPath: Option[CollectionPath], operation: String, state: String, objectCount: Option[Int], error: Option[Throwable]): Unit = synchronized {
    if (maybeStatusFile.isEmpty) {
      return
    }

    val timestamp = Instant.now().toString
    val key = recordKey(indexName, collectionPath)
    val previousLastSuccess = records.get(key).flatMap(node => Option(node.get("lastSuccessfulOperationTimestamp")).map(_.asText()))
    val node = mapper.createObjectNode()
    node.put("index", indexName)
    collectionPath.foreach(node.put("collection", _))
    node.put("operation", operation)
    node.put("state", state)
    node.put("timestamp", timestamp)
    if (state == CURRENT) {
      node.put("lastSuccessfulOperationTimestamp", timestamp)
    } else {
      previousLastSuccess.foreach(node.put("lastSuccessfulOperationTimestamp", _))
    }
    objectCount.foreach(node.put("objectCount", _))
    error.foreach { t =>
      node.put("failureClass", t.getClass.getName)
      node.put("failureMessage", Option(t.getMessage).getOrElse(""))
    }

    records.update(key, node)
    write()
  }

  private def recordKey(indexName: IndexName, collectionPath: Option[CollectionPath]): String =
    s"$indexName|${collectionPath.getOrElse("*")}"

  private def loadExisting(statusFile: Path): Unit = synchronized {
    if (!Files.isRegularFile(statusFile)) {
      return
    }

    val root = mapper.readTree(statusFile.toFile)
    val existingRecords = Option(root.get("records")).collect { case array: ArrayNode => array }
    existingRecords.foreach { array =>
      val elements = array.elements()
      while (elements.hasNext) {
        elements.next() match {
          case node: ObjectNode =>
          val indexName = Option(node.get("index")).map(_.asText()).getOrElse("")
          val collectionPath = Option(node.get("collection")).map(_.asText())
          if (indexName.nonEmpty) {
            records.update(recordKey(indexName, collectionPath), node)
          }
          case _ =>
        }
      }
    }
  }

  private def write(): Unit = {
    val statusFile = maybeStatusFile.get
    Files.createDirectories(statusFile.getParent)
    val root = mapper.createObjectNode()
    root.put("updatedAt", Instant.now().toString)
    val array = root.putArray("records")
    records.values.foreach(array.add)

    val tempFile = Files.createTempFile(statusFile.getParent, "status", ".json")
    Files.write(tempFile, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root).getBytes(StandardCharsets.UTF_8))
    Files.move(tempFile, statusFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
  }
}
