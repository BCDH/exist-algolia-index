package org.humanistika.exist.index.algolia

import java.nio.file.Files

import org.humanistika.exist.index.algolia.IndexableRootObjectJsonSerializer.COLLECTION_PATH_FIELD_NAME
import org.humanistika.exist.index.algolia.backend.AlgoliaIndexActor
import org.humanistika.exist.index.algolia.backend.AlgoliaIndexManagerActor.exactCollectionPathFilter
import org.humanistika.exist.index.algolia.backend.IndexLocalStoreDocumentActor.Changes
import org.specs2.mutable.Specification

class AlgoliaIndexManagerActorSpec extends Specification {

  "exactCollectionPathFilter" should {
    "build an exact filter for the collection field" in {
      exactCollectionPathFilter("/db/apps/raskovnik-data/data/MBRT.RDG") mustEqual
        s"""$COLLECTION_PATH_FIELD_NAME:${'"'}/db/apps/raskovnik-data/data/MBRT.RDG${'"'}"""
    }

    "escape quotes in collection paths" in {
      exactCollectionPathFilter("/db/apps/\"quoted\"") mustEqual
        s"""$COLLECTION_PATH_FIELD_NAME:${'"'}/db/apps/\\\"quoted\\\"${'"'}"""
    }
  }

  "AlgoliaIndexActor" should {
    "treat empty change sets as no-op batches" in {
      AlgoliaIndexActor.isEmptyBatch(Changes(42, Seq.empty, Seq.empty, Seq.empty)) must beTrue
    }

    "treat any actual addition, update, or deletion as non-empty" in {
      val tempFile = Files.createTempFile("algolia-index-batch", ".json")
      try {
        val rootObject = LocalIndexableRootObject(tempFile)

        AlgoliaIndexActor.isEmptyBatch(Changes(42, Seq(rootObject), Seq.empty, Seq.empty)) must beFalse
        AlgoliaIndexActor.isEmptyBatch(Changes(42, Seq.empty, Seq(rootObject), Seq.empty)) must beFalse
        AlgoliaIndexActor.isEmptyBatch(Changes(42, Seq.empty, Seq.empty, Seq("object-id"))) must beFalse
      } finally {
        Files.deleteIfExists(tempFile)
      }
    }
  }
}
