package org.humanistika.exist.index.algolia

import org.humanistika.exist.index.algolia.ExistAPIHelper._
import org.exist.xmldb.XmldbURI
import org.specs2.mutable.Specification

import scala.util.Using

class AlgoliaIndexWorkerSpec extends Specification with ExistServerForEach {
  instanceName = Some("algolia-index-worker-spec")

  "AlgoliaIndexWorker.removeCollection" should {
    "do nothing when the collection has no Algolia config" in new AkkaTestkitSpecs2Support {
      implicit val brokerPool: org.exist.storage.BrokerPool = getBrokerPool

      withBroker { broker =>
        withTxn { txn =>
          Using(broker.getOrCreateCollection(txn, XmldbURI.create("/db/test-no-algolia-config"))) { collection =>
            broker.saveCollection(txn, collection)

            val worker = new AlgoliaIndexWorker(null, broker, system, testActor)
            worker.removeCollection(collection, broker, reindex = false)
          }.get
        }
      }.flatMap(x => x) must beRight

      expectNoMessage()
    }
  }
}
