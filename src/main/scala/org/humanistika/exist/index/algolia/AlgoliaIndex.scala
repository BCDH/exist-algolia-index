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

package org.humanistika.exist.index.algolia

import java.nio.file.Path

import org.apache.logging.log4j.{LogManager, Logger}
import org.exist.indexing.{AbstractIndex, IndexWorker}
import org.exist.storage.{BrokerPool, DBBroker}
import org.w3c.dom.Element
import AlgoliaIndex._
import akka.actor.{ActorRef, ActorSystem, Props}
import org.humanistika.exist.index.algolia.backend.IncrementalIndexingManagerActor
import org.humanistika.exist.index.algolia.backend.IncrementalIndexingManagerActor.DropIndexes

import scala.collection.JavaConverters._
import scala.concurrent.Await

object AlgoliaIndex {
  private val LOG: Logger = LogManager.getLogger(classOf[AlgoliaIndex])
  val ID: String = AlgoliaIndex.getClass.getName
  val DEFAULT_SYSTEM_NAME = "AlgoliaIndex"
  case class Authentication(applicationId: String, adminApiKey: String)
}

/**
  * @param _system allows us to inject an ActorSystem for our tests
  * @param _incrementalIndexingManagerActor allows us to inject a TestKit ActorRef for our Tests
  */
class AlgoliaIndex(_system: Option[ActorSystem] = None, _incrementalIndexingManagerActor: Option[ActorRef] = None) extends AbstractIndex {
  private var system: Option[ActorSystem] = None
  private var incrementalIndexingManagerActor: Option[ActorRef] = None
  private var apiAuthentication: Option[Authentication] = None

  /**
    * Default constructor needed for use with eXist-db
    */
  def this() {
    this(None, None)
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

  override def open() {
    this.system = _system.orElse(Some(ActorSystem(DEFAULT_SYSTEM_NAME)))
    this.incrementalIndexingManagerActor = _incrementalIndexingManagerActor.orElse(system.map(_.actorOf(Props(classOf[IncrementalIndexingManagerActor], getDataDir), IncrementalIndexingManagerActor.ACTOR_NAME)))
    this.incrementalIndexingManagerActor.foreach(actor => apiAuthentication.foreach(auth => actor ! auth))

    // recommended by Algolia
    java.security.Security.setProperty("networkaddress.cache.ttl", "60")
  }

  override def close() {
    system match {
      case Some(sys) =>
        import scala.concurrent.duration._
        Await.ready(sys.terminate(), 2 minutes)
        system = None // remember we have stopped!

      case None =>
    }
  }

  override def getWorker(broker: DBBroker): IndexWorker = {
    system match {
      case Some(sys) =>
        incrementalIndexingManagerActor.map(new AlgoliaIndexWorker(this, broker, sys, _)).getOrElse(null)
      case _ =>
        null
    }
  }

  override def remove(): Unit = {
    incrementalIndexingManagerActor.foreach(_ ! DropIndexes)
  }

  override def checkIndex(broker: DBBroker) = false

  override def sync() {
    //TODO(AR) implement?
  }
}
