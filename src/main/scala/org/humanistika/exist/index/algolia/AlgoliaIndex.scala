package org.humanistika.exist.index.algolia

import java.nio.file.Path

import org.apache.logging.log4j.{LogManager, Logger}
import org.exist.indexing.{AbstractIndex, IndexWorker}
import org.exist.storage.{BrokerPool, DBBroker}
import org.w3c.dom.Element
import AlgoliaIndex._
import akka.actor.{ActorSystem, Props}
import com.algolia.search.AsyncHttpAPIClientBuilder
import org.humanistika.exist.index.algolia.backend.IncrementalIndexingActor

import scala.collection.JavaConverters._

object AlgoliaIndex {
  private val LOG: Logger = LogManager.getLogger(classOf[AlgoliaIndex])
  val ID: String = AlgoliaIndex.getClass.getName
  val SYSTEM_NAME = "AlgoliaIndex"
  case class Authentication(applicationId: String, adminApiKey: String)
}

class AlgoliaIndex extends AbstractIndex {
  private var system: Option[ActorSystem] = None
  private var apiAuthentication: Option[Authentication] = None

  override def open() {
    system = Some(ActorSystem(SYSTEM_NAME))
    system.foreach(_.actorOf(Props[IncrementalIndexingActor]))

    // recommended by Algolia
    java.security.Security.setProperty("networkaddress.cache.ttl", "60")
  }

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

  override def close() {
    system.foreach(_.shutdown())
  }

  override def getWorker(broker: DBBroker): IndexWorker = {
    system match {
      case Some(sys) if !sys.isTerminated =>
        new AlgoliaIndexWorker(this, broker, sys)
      case _ =>
        null
    }
  }

  def getAuthentication: Option[Authentication] = apiAuthentication

  override def remove(): Unit = {
    //delete all the Algolia indexes?
    getAuthentication match {
      case None =>
        LOG.error("Cannot remove Algolia indexes, no Authentication credentials provided")

      case Some(auth) =>
        val client = new AsyncHttpAPIClientBuilder(auth.applicationId, auth.adminApiKey)
          .build()

        val indexes = client.listIndices().get().asScala.map(indexAttributes => (indexAttributes.getName -> client.initIndex(indexAttributes.getName, classOf[IndexableRootObject])))

        //TODO(AR) currently synchronous, make async
        val futures = indexes.map(index => index._1 -> index._2.delete())
        val results = futures.map(future => future._1 -> future._2.get().getTaskID)

        LOG.info("Delete Algolia indexes")
        if(LOG.isTraceEnabled()) {
          results.foreach(result => LOG.trace("Deleted Algolia index: {}", result._1))
        }
    }
  }

  override def checkIndex(broker: DBBroker) = false

  override def sync() {
    //TODO(AR) implement?
  }
}
