package org.humanistika.exist.index.algolia

import java.nio.file.Files

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import org.exist.util.FileUtils
import org.humanistika.exist.index.algolia.backend.IndexingStatusStore
import org.specs2.mutable.Specification

import scala.collection.JavaConverters._

class IndexingStatusStoreSpec extends Specification {

  "IndexingStatusStore" should {
    "persist collection health across reloads" in {
      val dataDir = Files.createTempDirectory("algolia-index-status-store")
      val collectionOne = "/db/apps/raskovnik-data/data/GE.RKMD"
      val collectionTwo = "/db/apps/raskovnik-data/data/MV.RGP"

      try {
        val initialStore = IndexingStatusStore(dataDir)
        initialStore.markCurrent("ras", Some(collectionOne), IndexingStatusStore.BATCH_WRITE, Some(0))

        val reloadedStore = IndexingStatusStore(dataDir)
        reloadedStore.markDegraded("ras", Some(collectionTwo), IndexingStatusStore.BATCH_WRITE, new RuntimeException("boom"))

        val statusFile = dataDir.resolve("algolia-index").resolve("status.json")
        val root = new ObjectMapper().readTree(statusFile.toFile)
        root.get("healthContractVersion").asInt() mustEqual IndexingStatusStore.COLLECTION_HEALTH_VERSION
        val collectionHealth = root.get("collectionHealth").asInstanceOf[ArrayNode]
        collectionHealth.size() mustEqual 2
        collectionHealth.elements().asScala.find(_.get("collection").asText() == collectionOne).get.get("state").asText() mustEqual IndexingStatusStore.CURRENT
        collectionHealth.elements().asScala.find(_.get("collection").asText() == collectionTwo).get.get("state").asText() mustEqual IndexingStatusStore.DEGRADED
      } finally {
        FileUtils.deleteQuietly(dataDir)
      }
    }
  }
}
