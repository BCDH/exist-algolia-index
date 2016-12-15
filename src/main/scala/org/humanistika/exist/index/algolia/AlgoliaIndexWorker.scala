package org.humanistika.exist.index.algolia

import java.util
import javax.xml.bind.JAXBContext

import org.exist.collections.Collection
import org.exist.dom.persistent._
import org.exist.indexing.StreamListener.ReindexMode
import org.exist.indexing.{IndexController, IndexWorker}
import org.exist.storage.{DBBroker, NodePath}
import org.exist.util.Occurrences
import org.exist.xquery.XQueryContext
import org.exist_db.collection_config._1.Algolia
import org.w3c.dom.{Element, Node, NodeList}
import AlgoliaIndexWorker._
import akka.actor.ActorSystem
import com.algolia.search.AsyncHttpAPIClientBuilder
import com.algolia.search.objects.Query
import org.apache.logging.log4j.{LogManager, Logger}

import scala.collection.JavaConverters._

object AlgoliaIndexWorker {
  private val LOG: Logger = LogManager.getLogger(classOf[AlgoliaIndexWorker])

  private val CONFIG_ROOT_ELEMENT_NAME = "algolia"
  private val COLLECTION_CONFIG_NS = "http://exist-db.org/collection-config/1.0"

  case class Context(var document: Option[DocumentImpl], var mode: Option[ReindexMode])
}


class AlgoliaIndexWorker(index: AlgoliaIndex, broker: DBBroker, system: ActorSystem) extends IndexWorker {

  private var indexConfig: Option[Algolia] = None
  private val currentContext = Context(None, None)
  private val listener = new AlgoliaStreamListener(this, broker, system)

  def getIndex = index

  override def configure(indexController: IndexController, configNodes: NodeList, namespaces: java.util.Map[String, String]): AnyRef = {
    def filterConfigElement(node: Node): Option[Element] = {
      if(node.getNodeType == Node.ELEMENT_NODE
          && node.getLocalName == CONFIG_ROOT_ELEMENT_NAME
          && node.getNamespaceURI == COLLECTION_CONFIG_NS) {
        Some(node.asInstanceOf[Element])
      } else {
        None
      }
    }

    def loadJaxbConfig(algoliaConfigElement: Element): Option[Algolia] = {
      val jaxb = JAXBContext.newInstance(classOf[Algolia].getPackage.getName)
      val unmarshaller = jaxb.createUnmarshaller()
      Some(unmarshaller.unmarshal(algoliaConfigElement).asInstanceOf[Algolia])
    }

    val algoliaConfigElements = for(idx <- (0 until configNodes.getLength))
      yield filterConfigElement(configNodes.item(idx))

    val algoliaConfigElement = algoliaConfigElements
      .foldLeft(Seq.empty[Element])((accum, x) => if(x.nonEmpty) accum :+ x.get else accum)
      .headOption

    this.indexConfig = algoliaConfigElement.flatMap(loadJaxbConfig(_))
    this.indexConfig.map(listener.configure)

    // return the index config which will be kept by eXist for later calls to {@link #setDocument(document)
    indexConfig.getOrElse(null)
  }

  override def getDocument = currentContext.document.getOrElse(null)

  override def setDocument(document: DocumentImpl) = setDocument(document, ReindexMode.UNKNOWN)

  override def setDocument(document: DocumentImpl, mode: ReindexMode) {
    currentContext.document = Option(document)
    currentContext.mode = Some(mode)

    this.indexConfig = Option(document.getCollection().getIndexConfiguration(broker))
          .flatMap(indexSpec => Option(indexSpec.getCustomIndexSpec(AlgoliaIndex.ID).asInstanceOf[Algolia]))
    this.indexConfig.map(listener.configure)
  }

  override def getMode = currentContext.mode.getOrElse(ReindexMode.UNKNOWN)

  override def setMode(mode: ReindexMode) {
    currentContext.mode = Some(mode)
  }

  override def flush() {
    //TODO(AR) implement?
  }

  override def checkIndex(broker: DBBroker) = false

  override def scanIndex(context: XQueryContext, documentSet: DocumentSet, nodeSet: NodeSet, map: java.util.Map[_, _]): Array[Occurrences] = ???

  override def getQueryRewriter(context: XQueryContext) = null
  override def getMatchListener(broker: DBBroker, nodeProxy: NodeProxy) = null //TODO(AR) implement if we want to support Kwic etc

  override def removeCollection(collection: Collection, broker: DBBroker, reindex: Boolean) {
    val maybeCollectionAlgoliaConf: Option[Algolia] = Option(collection.getConfiguration(broker))
        .flatMap(collectionConfig => Option(collectionConfig.getIndexConfiguration)
          .flatMap(indexSpec => Option(indexSpec.getCustomIndexSpec(AlgoliaIndex.ID)).map(_.asInstanceOf[Algolia])))

    maybeCollectionAlgoliaConf match {
      case None =>
        LOG.error("Cannot remove Algolia indexes for collection {}, no collection config found!", collection.getURI)

      case Some(collectionAlgoliaConf) =>
        index.getAuthentication match {
          case None =>
            LOG.error("Cannot remove Algolia indexes for collection {}, no Authentication credentials provided", collection.getURI)

          case Some(auth) =>
            val client = new AsyncHttpAPIClientBuilder(auth.applicationId, auth.adminApiKey)
              .build()


            val indexNames = collectionAlgoliaConf.getIndex.asScala.map(_.getName)
            for (indexName <- indexNames) {
              val index = client.initIndex(indexName, classOf[IndexableRootObject])
              val queryByCollection = new Query()
              queryByCollection.setAttributesToRetrieve(util.Arrays.asList("objectId"))

              //TODO(AR) Async API has no deleteByQuery - switch to sync API and wrap in our own Async stuff!

              //TODO(AR) how to search by part of objectID? otherwise maybe store collectionID separately and search on that?

//              /queryByCollection.
//              index.search()
//              index.deleteObjects()
            }
        }
    }

    //TODO(AR) implement - remove all entries for this collection
  }

  override def getReindexRoot[T <: IStoredNode[_]](node: IStoredNode[T], nodePath: NodePath, insert: Boolean, includeSelf: Boolean): IStoredNode[_] = ???

  override def getIndexId: String = AlgoliaIndex.ID

  override def getIndexName = index.getIndexName

  override def getListener = listener

  def getConfig = indexConfig
}
