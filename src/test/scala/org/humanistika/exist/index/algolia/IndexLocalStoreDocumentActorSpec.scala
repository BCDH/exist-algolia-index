package org.humanistika.exist.index.algolia

import org.humanistika.exist.index.algolia.backend.IndexLocalStoreDocumentActor
import org.specs2.Specification

class IndexLocalStoreDocumentActorSpec extends Specification { def is = s2"""
  This is a specification to check checksum comparisons in IndexLocalStoreDocumentActor

    sameChecksum
      treats identical checksum bytes as unchanged $e1
      treats different checksum bytes as changed $e2
      returns checksum failures instead of pretending there was an update $e3
"""

  def e1 = {
    IndexLocalStoreDocumentActor.sameChecksum(
      Right(Array[Byte](1, 2, 3)),
      Right(Array[Byte](1, 2, 3))
    ) must beRight(true)
  }

  def e2 = {
    IndexLocalStoreDocumentActor.sameChecksum(
      Right(Array[Byte](1, 2, 3)),
      Right(Array[Byte](1, 2, 4))
    ) must beRight(false)
  }

  def e3 = {
    val checksumFailure = new IllegalStateException("boom")
    IndexLocalStoreDocumentActor.sameChecksum(
      Left(checksumFailure),
      Right(Array[Byte](1, 2, 3))
    ) must beLeft(Seq(checksumFailure))
  }
}
