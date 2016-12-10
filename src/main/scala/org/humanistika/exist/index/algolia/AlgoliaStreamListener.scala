package org.humanistika.exist.index.algolia

import java.util.{Map => JMap, HashMap => JHashMap}

import org.exist.dom.persistent.{AttrImpl, ElementImpl}
import org.exist.indexing.AbstractStreamListener
import org.exist.storage.NodePath
import org.exist.storage.txn.Txn
import AlgoliaStreamListener._
import com.algolia.search.{AsyncHttpAPIClientBuilder, AsyncIndex}
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import org.apache.logging.log4j.{LogManager, Logger}
import org.exist_db.collection_config._1.{Algolia, LiteralType, RootObject}
import org.exist_db.collection_config._1.LiteralType._
import org.w3c.dom.{Attr, Element}

import scala.collection.JavaConverters._
import scalaz._
import Scalaz._


object AlgoliaStreamListener {

  private val LOG: Logger = LogManager.getLogger(classOf[AlgoliaStreamListener])


  /**
    * Additional functions for {@link or.exist.storage.NodePath}
    */
  implicit class NodePathUtils(nodePath: NodePath) {

    /**
      * Does this NodePath startWith another NodePath?
      *
      * @return true if this nodepath starts with other
      */
    def startsWith(other: NodePath): Boolean = {
      if (nodePath.length() < other.length()) {
        false
      } else {
        def notEqual(index: Int): Boolean = !nodePath.getComponent(index).equals(other.getComponent(index))
        val notStartsWith = (0 until other.length()).find(notEqual)
        notStartsWith.empty
      }
    }

    def duplicate() = new NodePath(nodePath)

    /**
      * Creates a new NodePath which is equivalent
      * to the path of /a/b
      */
    def appendNew(other: NodePath): NodePath = {
      val result = nodePath.duplicate()
      result.append(other)
      result
    }
  }

  val DOCUMENT_NODE_PATH = new NodePath()

  type NamespacePrefix = String
  type NamespaceUri = String

  def nodePath(ns: JMap[NamespacePrefix, NamespaceUri], path: String): NodePath = {
    Option(path)
      .filterNot(_ == "/")
      .map(new NodePath(ns, _))
      .getOrElse(new NodePath())
  }

  case class PartialRootObject(indexName: IndexName, config: RootObject, indexable: IndexableRootObject) {
    def identityEquals(other: PartialRootObject) : Boolean = {
      indexName == other.indexName &&
        indexable.documentId.equals(other.indexable.documentId) &&
          indexable.nodeId.equals(other.indexable.nodeId)
    }
  }

  def typeOrDefault(literalType: LiteralType): LiteralTypeConfig.LiteralTypeConfig = {
    Option(literalType) match {
      case Some(INTEGER) =>
        LiteralTypeConfig.Integer
      case Some(FLOAT) =>
        LiteralTypeConfig.Float
      case Some(BOOLEAN) =>
        LiteralTypeConfig.Boolean
      case Some(DATE) =>
        LiteralTypeConfig.Date
      case Some(DATE_TIME) =>
        LiteralTypeConfig.DateTime
      case _ =>
        LiteralTypeConfig.String
    }
  }

  private def getNamespaceMappings(config: Algolia) : Map[NamespacePrefix, NamespaceUri] = {
    Option(config.getNamespaceMappings)
      .map(_.getNamespaceMapping.asScala.map(nsm => nsm.getPrefix -> nsm.getNamespace).toMap)
      .getOrElse(Map.empty)
  }
}

class AlgoliaStreamListener(indexWorker: AlgoliaIndexWorker) extends AbstractStreamListener {

  private val ns: JMap[String, String] = new JHashMap
  private var rootObjectConfigs: Seq[(IndexName, RootObject)] = Seq.empty

  private var processing: Map[NodePath, Seq[PartialRootObject]] = Map.empty
  private var indexes: Map[IndexName, AsyncIndex[IndexableRootObject]] = Map.empty


  def configure(config: Algolia) {
    this.ns.clear()
    getNamespaceMappings(config).foreach { case (k, v) => ns.put(k, v) }
    this.rootObjectConfigs = config.getIndex.asScala.flatMap(index => index.getRootObject.asScala.map(rootObject => (index.getName, rootObject)))
  }

  override def getWorker = indexWorker

  override def startIndexDocument(transaction: Txn) {

    // find any RootObjects that we should start processing
    val documentRootObjects = getRootObjectConfigs(isDocumentRootObject)

    if (documentRootObjects.nonEmpty) {
      // as we are just starting a document,
      // we aren't processing these yet, so let's record them
      val processingAtPath = documentRootObjects.map(rootObjectConfig => PartialRootObject(rootObjectConfig._1, rootObjectConfig._2, IndexableRootObject(indexWorker.getDocument().getCollection.getId, indexWorker.getDocument().getDocId, None, Seq.empty)))
      this.processing = processing + (DOCUMENT_NODE_PATH -> processingAtPath)
    }

    super.startIndexDocument(transaction)
  }

  override def startElement(transaction: Txn, element: ElementImpl, path: NodePath) {
    val pathClone = new NodePath(path)

    // find any new RootObjects that we should start processing
    val elementRootObjects = getRootObjectConfigs(isElementRootObject(pathClone))

    if (elementRootObjects.nonEmpty) {
      // record the new RootObjects that we are processing
      val newElementRootObjects: Seq[PartialRootObject] = elementRootObjects.map(rootObjectConfig => PartialRootObject(rootObjectConfig._1, rootObjectConfig._2, IndexableRootObject(indexWorker.getDocument().getCollection.getId, indexWorker.getDocument().getDocId, Some(element.getNodeId.toString), Seq.empty)))
      val processingAtPath = processing.get(pathClone) match {
        case Some(existingElementRootObjects) =>
          // we filter out newElementRootObjects that are equivalent to elementRootObjects which we are already processing
          existingElementRootObjects ++ newElementRootObjects.filterNot(newElementRootObject => existingElementRootObjects.find(_.identityEquals(newElementRootObject)).empty)
        case None =>
          newElementRootObjects
      }
      this.processing = processing + (pathClone -> processingAtPath)
    }

    // update any PartialRootObjects children which match this element
    updateProcessingChildren(pathClone, element.left)

    super.startElement(transaction, element, path)
  }

  override def attribute(transaction: Txn, attrib: AttrImpl, path: NodePath) {
    val pathClone = new NodePath(path)
    pathClone.addComponent(attrib.getQName)

    // update any PartialRootObjects children which match this element
    updateProcessingChildren(pathClone, attrib.right)

    super.attribute(transaction, attrib, pathClone)
  }

  override def endElement(transaction: Txn, element: ElementImpl, path: NodePath) {
    val pathClone = new NodePath(path)

    // find any new RootObjects that we should finish processing
    val elementRootObjects = processing.get(pathClone).getOrElse(Seq.empty)

    if (elementRootObjects.nonEmpty) {
      // index them
      elementRootObjects
        .map(partialRootObject => index(partialRootObject.indexName, partialRootObject.indexable))

      // finished... so remove them from the map of things we are processing
      this.processing = processing.filterKeys(_ != pathClone)
    }

    super.endElement(transaction, element, path)
  }

  override def endIndexDocument(transaction: Txn) {

    // find any new RootObjects that we should finish processing
    val documentRootObjects = processing.get(DOCUMENT_NODE_PATH).getOrElse(Seq.empty)

    if (documentRootObjects.nonEmpty) {
      // index them
      documentRootObjects
        .map(partialRootObject => index(partialRootObject.indexName, partialRootObject.indexable))

      // finished... so remove them from the map of things we are processing
      this.processing = processing.filterKeys(_ != DOCUMENT_NODE_PATH)
    }

    super.endIndexDocument(transaction)
  }

  private def isDocumentRootObject(rootObject: RootObject) = {
    Option(rootObject.getPath)
      .filterNot(path => path.isEmpty || path.equals("/"))
      .isEmpty
  }

  private def isElementRootObject(path: NodePath)(rootObject: RootObject) = nodePath(ns, rootObject.getPath) == path

  private def updateProcessingChildren(path: NodePath, node: ElementOrAttributeImpl) {
    // find any PartialRootObjects which *may* have objects or attributes that match this element or attribute
    val ofInterest = processing
      .filterKeys(path.startsWith(_))

    // update any PartialRootObjects children which match this element or attribute
    for (
      (rootObjectNodePath, partialRootObjects) <- ofInterest;
      partialRootObject <- partialRootObjects
    ) {
      val nodeId = node.fold(_.getNodeId, _.getNodeId).toString

      val attributesConfig = partialRootObject.config.getAttribute.asScala
        .filter(attrConf => rootObjectNodePath.appendNew(nodePath(ns, attrConf.getPath)).equals(path))
      val attributes: Seq[IndexableAttribute] = attributesConfig.map(attrConfig => IndexableAttribute(attrConfig.getName, nodeId, node, typeOrDefault(attrConfig.getType)))

      val objectsConfig = partialRootObject.config.getObject.asScala
        .filter(obj => rootObjectNodePath.appendNew(nodePath(ns, obj.getPath)).equals(path))
      val objects: Seq[IndexableObject] = objectsConfig.map(objectConfig => IndexableObject(objectConfig.getName, nodeId, node, getObjectMappings(objectConfig)))

      if(attributes.nonEmpty || objects.nonEmpty) {
        val newChildren: Seq[IndexableAttribute \/ IndexableObject] = partialRootObject.indexable.children ++ objects.map(_.right) ++ attributes.map(_.left)
        val newPartialRootObject = partialRootObject.copy(indexable = partialRootObject.indexable.copy(children = newChildren))
        val newPartialRootObjects = this.processing(rootObjectNodePath).filterNot(_ == partialRootObject) :+ newPartialRootObject

        this.processing = this.processing + (rootObjectNodePath -> newPartialRootObjects)
      }
    }
  }

  private def getObjectMappings(objectConfig: org.exist_db.collection_config._1.Object): Map[NodePath, (LiteralTypeConfig.LiteralTypeConfig, Option[Name])] = objectConfig.getMapping.asScala.map(mapping => (nodePath(ns, mapping.getPath) -> (typeOrDefault(mapping.getType), Option(mapping.getName)))).toMap

  private def getRootObjectConfigs(filter: RootObject => Boolean): Seq[(IndexName, RootObject)] = rootObjectConfigs.filter { case (_, rootObject) => filter(rootObject) }

  //INDEX IT!
  private def index(indexName: IndexName, indexableRootObject: IndexableRootObject) {
    indexWorker.getIndex.getAuthentication match {
      case None =>
        LOG.error("Cannot add object to Algolia index, no Authentication credentials provided")

      case Some(auth) =>
        val client = new AsyncHttpAPIClientBuilder(auth.applicationId, auth.adminApiKey)
          //TODO(AR) what other config should we support?
          //.setObjectMapper(indexableRootObjectMapper)
          .build()

        val index = indexes.get(indexName) match {
          case Some(idx) => idx
          case None =>
            val idx = client.initIndex(indexName, classOf[IndexableRootObject])
            indexes += (indexName -> idx)
            idx
        }

        //TODO(AR) batch up objects
        val futIndexedObject = index.addObject(indexableRootObject)

        //TODO(AR) make async... .get will Await
        val indexedId = futIndexedObject.get().getObjectID
        if(LOG.isTraceEnabled()) {
          LOG.trace("Indexed: {}", indexedId)
        }
    }
  }
}
