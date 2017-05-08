/**
  * Copyright (C) 2017, Adam Retter <adam.retter@googlemail.com>
  * All rights reserved.
  *
  * Redistribution and use in source and binary forms, with or without
  * modification, are permitted provided that the following conditions are met:
  * * Redistributions of source code must retain the above copyright
  * notice, this list of conditions and the following disclaimer.
  * * Redistributions in binary form must reproduce the above copyright
  * notice, this list of conditions and the following disclaimer in the
  * documentation and/or other materials provided with the distribution.
  * * Neither the name of the <organization> nor the
  * names of its contributors may be used to endorse or promote products
  * derived from this software without specific prior written permission.
  *
  * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
  * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
  * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
  * DISCLAIMED. IN NO EVENT SHALL <COPYRIGHT HOLDER> BE LIABLE FOR ANY
  * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
  * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
  * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
  * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
  * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
  * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
  */
package org.humanistika.exist.index.algolia

import java.io.IOException
import java.nio.file.{Files, Path, Paths}
import java.util.{Properties, Optional => JOptional}

import org.exist.EXistException

import scala.collection.JavaConverters._
import org.exist.storage.{BrokerPool, DBBroker, ScalaBrokerPoolBridge}
import org.exist.repo.AutoDeploymentTrigger.AUTODEPLOY_PROPERTY
import org.exist.storage.journal.Journal
import org.exist.storage.txn.Txn
import org.exist.util.{Configuration, ConfigurationHelper, DatabaseConfigurationException, FileUtils}
import org.specs2.specification.{BeforeAfterAll, BeforeAfterEach}

import scalaz.\/

/**
  * Helper traits for integration testing eXist-db with Specs2
  * Not dissimilar to {@link org.exist.test.ExistEmbeddedServer}
  *
  * @author Adam Retter <adam.retter@googlemail.com>
  */
trait ExistServerForAll extends BeforeAfterAll with ExistServerStartStopHelper {
  override def beforeAll(): Unit = startDb()
  override def afterAll(): Unit = stopDb()
}

trait ExistServerForEach extends BeforeAfterEach with ExistServerStartStopHelper {
  override protected def before: Any = startDb()
  override protected def after: Any = stopDb()
}

trait ExistServerStartStopHelper {
  var instanceName: Option[String] = None
  val configFile: Option[Path] = None
  var configProperties: Option[Properties] = None
  var useTemporaryStorage: Boolean = false
  var disableAutoDeploy: Boolean = true

  private var temporaryStorage : Option[Path] = None
  private var prevAutoDeploy : Option[String] = None
  private var activePool : Option[BrokerPool] = None

  implicit class OptionAsJava[T](option: Option[T]) {
    def asJava: java.util.Optional[T] = {
      option match {
        case Some(t) => java.util.Optional.of(t)
        case None => java.util.Optional.empty()
      }
    }
  }

  @throws[IllegalStateException]
  @throws[DatabaseConfigurationException]
  @throws[EXistException]
  @throws[IOException]
  def startDb() {
    activePool match {
      case Some(pool) =>
        throw new IllegalStateException("ExistEmbeddedServer already running")

      case None =>
        if (disableAutoDeploy) {
          this.prevAutoDeploy = Some(System.getProperty(AUTODEPLOY_PROPERTY, "off"))
          System.setProperty(AUTODEPLOY_PROPERTY, "off")
        }


        val name = instanceName.getOrElse(ScalaBrokerPoolBridge.DEFAULT_INSTANCE_NAME)
        val home = Option(System.getProperty("exist.home", System.getProperty("user.dir"))).map(Paths.get(_))
        val confFile = configFile.getOrElse(ConfigurationHelper.lookup("conf.xml", home.asJava))

        val config : Configuration =
          if(confFile.isAbsolute() && Files.exists(confFile)) {
            new Configuration(confFile.toAbsolutePath().toString())
          } else {
            new Configuration(FileUtils.fileName(confFile), home.asJava)
          }


        // override any specified config properties
        for(
            cfg <- configProperties;
            entry <- cfg.entrySet().asScala) {
          config.setProperty(entry.getKey.toString, entry.getValue)
        }

        if(useTemporaryStorage) {
          this.temporaryStorage = Option(Files.createTempDirectory("org.exist.test.ExistEmbeddedServer"))
          config.setProperty(ScalaBrokerPoolBridge.PROPERTY_DATA_DIR, temporaryStorage.get)
          config.setProperty(Journal.RECOVERY_JOURNAL_DIR_ATTRIBUTE, temporaryStorage.get)
          System.out.println("Using temporary storage location: " + temporaryStorage.get.toAbsolutePath().toString())
        }

        ScalaBrokerPoolBridge.configure(name, 1, 5, config, JOptional.empty())
        activePool = Some(ScalaBrokerPoolBridge.getInstance(name))
    }
  }

  @throws[IllegalStateException]
  def getBrokerPool : BrokerPool = {
    activePool match {
      case Some(pool) => pool
      case None =>
        throw new IllegalStateException("ExistEmbeddedServer is stopped")
    }
  }

  @throws[IllegalStateException]
  @throws[DatabaseConfigurationException]
  @throws[EXistException]
  @throws[IOException]
  def restartDb() {
    stopDb()
    startDb()
  }

  @throws[IllegalStateException]
  def stopDb() {
    activePool match {
      case None =>
        throw new IllegalStateException("ExistEmbeddedServer already stopped")

      case Some(pool) =>
        pool.shutdown()

        // clear instance variables
        activePool = None

        temporaryStorage match {
          case Some(tempStrorage) =>
            FileUtils.deleteQuietly(tempStrorage)
            temporaryStorage = None

          case None =>
        }

        if(disableAutoDeploy) {
          //set the autodeploy trigger enablement back to how it was before this test class
          System.setProperty(AUTODEPLOY_PROPERTY, prevAutoDeploy.getOrElse("off"))
        }
    }
  }
}

object ExistAPIHelper {
  import scalaz._
  import Scalaz._

  def withBroker[T](f: DBBroker => T)(implicit brokerPool: BrokerPool): \/[Exception, T] = {
    val broker = brokerPool.get(java.util.Optional.of(brokerPool.getSecurityManager.getSystemSubject))
    try {
      f(broker).right
    } catch {
      case e: Exception =>
        e.left
    } finally {
      ScalaBrokerPoolBridge.release(brokerPool, broker)
    }
  }

  def withTxn[T](f: Txn => T)(implicit brokerPool: BrokerPool): \/[Exception, T] = {
    val txnMgr = brokerPool.getTransactionManager
    val txn = txnMgr.beginTransaction()
    try {
      val result = f(txn).right
      txn.commit()
      result
    } catch {
      case e: Exception =>
        txn.abort()
        e.left
    } finally {
      txn.close()
    }
  }
}
