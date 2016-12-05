package org.humanistika.exist.index.algolia

import java.nio.file.Path

import org.apache.logging.log4j.{LogManager, Logger}
import org.exist.indexing.{AbstractIndex, IndexWorker}
import org.exist.storage.{BrokerPool, DBBroker}
import org.exist_db.collection_config._1.Algolia
import org.w3c.dom.Element
import AlgoliaIndex._

object AlgoliaIndex {
  private val LOG: Logger = LogManager.getLogger(classOf[AlgoliaIndex])
  val ID = AlgoliaIndex.getClass.getName

  case class Authentication(applicationId: String, adminApiKey: String)
}

class AlgoliaIndex extends AbstractIndex {
  private var apiAuthentication: Option[Authentication] = None

  override def open() {}

  override def configure(pool: BrokerPool, dataDir: Path, config: Element) {
    // get the authentication credentials from the config
    val applicationId = Option(config.getAttribute("application-id"))
    val adminApiKey = Option(config.getAttribute("admin-api-key"))
    if(applicationId.isEmpty) {
      LOG.error("You must specify an Application ID for use with Algolia")
    }
    if(adminApiKey.isEmpty) {
      LOG.error("You must specify an Admin API Key for use with Algolia")
    }
    this.apiAuthentication = applicationId.flatMap(id => adminApiKey.map(key => Authentication(id, key)))

    super.configure(pool, dataDir, config)
  }

  override def close() {}

  override def getWorker(broker: DBBroker): IndexWorker = new AlgoliaIndexWorker(this)

  def getAuthentication = apiAuthentication

  override def remove() {
    //TODO(AR) delete all the Algolia indexes?
  }

  override def checkIndex(broker: DBBroker) = false

  override def sync() {
    //TODO(AR) implement?
  }
}
