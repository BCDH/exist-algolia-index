package org.humanistika.exist.index.algolia

import java.util.{Properties, HashMap => JHashMap, Map => JMap}

import org.exist.dom.persistent.{AttrImpl, ElementImpl, NodeProxy}
import org.exist.indexing.AbstractStreamListener
import org.exist.storage.{DBBroker, NodePath}
import org.exist.storage.txn.Txn
import AlgoliaStreamListener._
import org.apache.logging.log4j.{LogManager, Logger}
import org.exist.dom.memtree.{DocumentBuilderReceiver, MemTreeBuilder}
import org.exist_db.collection_config._1.{Algolia, LiteralType, RootObject}
import org.exist_db.collection_config._1.LiteralType._
import Serializer._
import akka.actor.{ActorPath, ActorSystem}
import org.humanistika.exist.index.algolia.backend.IncrementalIndexingManagerActor
import org.humanistika.exist.index.algolia.backend.IncrementalIndexingManagerActor.{Add, FinishDocument, StartDocument}

import scala.collection.JavaConverters._
import scalaz._
import Scalaz._


object AlgoliaStreamListener {

  private val LOG: Logger = LogManager.getLogger(classOf[AlgoliaStreamListener])

  implicit class ElementImplUtils(element: org.exist.dom.persistent.ElementImpl) {
    def toInMemory(broker: DBBroker) : org.exist.dom.memtree.ElementImpl = {
      val builder = new MemTreeBuilder
      builder.startDocument()
      val receiver = new DocumentBuilderReceiver(builder, true)

      val nodeNr = builder.getDocument.getLastNode
      val nodeProxy = new NodeProxy(element.getOwnerDocument, element.getNodeId)
      nodeProxy.toSAX(broker, receiver, new Properties())

      builder.getDocument.getNode(nodeNr + 1).asInstanceOf[org.exist.dom.memtree.ElementImpl]
    }
  }

  implicit class AttrImplUtils(attr: org.exist.dom.persistent.AttrImpl) {
    def toInMemory(broker: DBBroker) : org.exist.dom.memtree.AttrImpl = {
      val element = attr.getParentNode.asInstanceOf[ElementImpl].toInMemory(broker)
      Option(attr.getNamespaceURI) match {
        case Some(ns) =>
          element.getAttributeNodeNS(ns, attr.getLocalName).asInstanceOf[org.exist.dom.memtree.AttrImpl]
        case None =>
          element.getAttributeNode(attr.getNodeName).asInstanceOf[org.exist.dom.memtree.AttrImpl]
      }
    }
  }

  /**
    * Additional functions for
    * {@link or.exist.storage.NodePath}
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

    def duplicate = new NodePath(nodePath)

    /**
      * Creates a new NodePath which is equivalent
      * to the path of /a/b
      */
    def appendNew(other: NodePath): NodePath = {
      val result = nodePath.duplicate
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

  case class UserSpecifiedDocumentId(path: NodePath, value: Option[String])

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

class AlgoliaStreamListener(indexWorker: AlgoliaIndexWorker, broker: DBBroker, system: ActorSystem) extends AbstractStreamListener {

  private val incrementalIndexingActor = system.actorSelection(ActorPath.fromString(s"akka://${AlgoliaIndex.SYSTEM_NAME}/user/${IncrementalIndexingManagerActor.ACTOR_NAME}"))

  private val ns: JMap[String, String] = new JHashMap
  private var indexConfigs: Map[IndexName, org.exist_db.collection_config._1.Index] = Map.empty
  private var rootObjectConfigs: Seq[(IndexName, RootObject)] = Seq.empty

  private var processing: Map[NodePath, Seq[PartialRootObject]] = Map.empty
  private var userSpecifiedDocumentIds: Map[IndexName, UserSpecifiedDocumentId] = Map.empty


  def configure(config: Algolia) {
    this.ns.clear()
    getNamespaceMappings(config).foreach { case (k, v) => ns.put(k, v) }
    this.rootObjectConfigs = config.getIndex.asScala.flatMap(index => index.getRootObject.asScala.map(rootObject => (index.getName, rootObject)))
    this.indexConfigs = config.getIndex.asScala.map(index => index.getName -> index).toMap
  }

  override def getWorker: AlgoliaIndexWorker = indexWorker

  override def startIndexDocument(transaction: Txn) {
    // start indexing any documents for which we have IndexableRootObjects
    indexConfigs.keys.foreach(indexName => startIndexDocument(indexName, indexWorker.getDocument.getCollection.getId, indexWorker.getDocument.getDocId))

    // find any User Specified Document IDs that we need to complete
    this.userSpecifiedDocumentIds = indexConfigs
        .map{ case (indexName, index) => indexName -> Option(index.getDocumentId).map(path => UserSpecifiedDocumentId(nodePath(ns, path), None)) }
        .collect{ case (indexName, Some(usdid)) => indexName -> usdid }

    // find any RootObjects that we should start processing
    val documentRootObjects = getRootObjectConfigs(isDocumentRootObject)

    if (documentRootObjects.nonEmpty) {
      // as we are just starting a document,
      // we aren't processing these yet, so let's record them
      val processingAtPath = documentRootObjects.map(rootObjectConfig => PartialRootObject(rootObjectConfig._1, rootObjectConfig._2, IndexableRootObject(indexWorker.getDocument.getCollection.getURI.getCollectionPath, indexWorker.getDocument().getCollection.getId, indexWorker.getDocument().getDocId, None, None, Seq.empty)))
      this.processing = processing + (DOCUMENT_NODE_PATH -> processingAtPath)
    }

    super.startIndexDocument(transaction)
  }

  override def startElement(transaction: Txn, element: ElementImpl, path: NodePath) {
    def getUserProvidedNodeId(rootObjectConfig: RootObject, element: ElementImpl): Option[String] = {
      // get the nodeId attribute value for rootObjectConfig from element
      Option(rootObjectConfig.getNodeId)
        .flatMap {
          case attrName if attrName.contains(':') =>
            val sepIdx = attrName.indexOf(':')
            val prefix = attrName.substring(0, sepIdx)
            val localPart = attrName.substring(sepIdx + 1)
            Option(element.getAttributeNS(ns.get(prefix), localPart))

          case attrName =>
            Option(element.getAttribute(attrName))
        }
    }

    val pathClone = new NodePath(path)

    // update any userSpecifiedDocumentIds which we haven't yet completed and that match this element path
    for ((indexName, usdid) <- userSpecifiedDocumentIds if usdid.value.isEmpty && usdid.path.equals(pathClone)) {
      this.userSpecifiedDocumentIds = userSpecifiedDocumentIds + (indexName -> usdid.copy(value = Some(getString(element.left))))
    }

    // find any new RootObjects that we should start processing
    val elementRootObjects = getRootObjectConfigs(isElementRootObject(pathClone))
    if (elementRootObjects.nonEmpty) {
      // record the new RootObjects that we are processing
      val newElementRootObjects: Seq[PartialRootObject] = elementRootObjects.map(rootObjectConfig => PartialRootObject(rootObjectConfig._1, rootObjectConfig._2, IndexableRootObject(indexWorker.getDocument().getCollection.getURI.getCollectionPath, indexWorker.getDocument().getCollection.getId, indexWorker.getDocument().getDocId, Some(element.getNodeId.toString), getUserProvidedNodeId(rootObjectConfig._2, element), Seq.empty)))
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

    // update any userSpecifiedDocumentIds which we haven't yet completed and that match this element path
    for ((indexName, usdid) <- userSpecifiedDocumentIds if usdid.value.isEmpty && usdid.path.equals(pathClone)) {
      this.userSpecifiedDocumentIds = userSpecifiedDocumentIds + (indexName -> usdid.copy(value = Some(getString(attrib.right))))
    }

    // update any PartialRootObjects children which match this element
    updateProcessingChildren(pathClone, attrib.right)

    super.attribute(transaction, attrib, pathClone)
  }

  override def endElement(transaction: Txn, element: ElementImpl, path: NodePath) {
    val pathClone = new NodePath(path)

    // find any new RootObjects that we should finish processing
    // they must match the nodePath and also have a userSpecifiedDocumentId
    // if configured to do so
    val elementRootObjects = processing.getOrElse(pathClone, Seq.empty)
          .filterNot(partialRootObject => userSpecifiedDocumentIds.get(partialRootObject.indexName).exists(_.value.isEmpty))
    if (elementRootObjects.nonEmpty) {
      // index them
      elementRootObjects
        .foreach(partialRootObject => index(partialRootObject.indexName, userSpecifiedDocumentIds.get(partialRootObject.indexName).flatMap(_.value), partialRootObject.indexable))

      // finished... so remove them from the map of things we are processing
      this.processing = processing.filterKeys(_ != pathClone)
    }

    super.endElement(transaction, element, path)
  }

  override def endIndexDocument(transaction: Txn) {

    // find any outstanding RootObjects that we should finish processing
    val documentRootObjects = processing.values.flatten

    if (documentRootObjects.nonEmpty) {
      // index them
      documentRootObjects
        .foreach(partialRootObject => index(partialRootObject.indexName, userSpecifiedDocumentIds.get(partialRootObject.indexName).flatMap(_.value), partialRootObject.indexable))

    }

    // finish indexing any documents for which we have IndexableRootObjects
    indexConfigs.keys.foreach(indexName => finishDocumentIndex(indexName, userSpecifiedDocumentIds.get(indexName).flatMap(_.value), indexWorker.getDocument.getCollection.getId, indexWorker.getDocument.getDocId))

    // finished... so clear the map of things we are processing
    this.processing = Map.empty

    // clear any User Specified Document IDs
    this.userSpecifiedDocumentIds = Map.empty

    super.endIndexDocument(transaction)
  }

  private def isDocumentRootObject(rootObject: RootObject): Boolean = Option(rootObject.getPath).forall(path => path.isEmpty || path.equals("/"))

  private def isElementRootObject(path: NodePath)(rootObject: RootObject) = nodePath(ns, rootObject.getPath) == path

  private def getString(node: ElementOrAttributeImpl): String = {
    node match {
      case -\/(element) =>
        serializeAsText(element)
      case \/-(attribute) =>
        attribute.getValue
    }
  }

  private def updateProcessingChildren(path: NodePath, node: ElementOrAttributeImpl) {

    def nodeIdStr(node: ElementOrAttributeImpl) : String = node.fold(_.getNodeId.toString, _.getNodeId.toString)

    def mergeIndexableChildren(existingChildren: Seq[IndexableAttributeOrObject], newChildren: Seq[IndexableAttributeOrObject]): Seq[IndexableAttributeOrObject] = {

      def name(node: IndexableAttributeOrObject): String = node.fold(_.name, _.name)

      def getMatchingNewChildren(existingChild: IndexableAttributeOrObject): Seq[IndexableAttributeOrObject] = {
        newChildren.collect {
          case la @ -\/(newIndexableAttribute) if existingChild.isLeft && name(existingChild) == newIndexableAttribute.name =>
            la
          case ro @ \/-(newIndexableObject) if existingChild.isRight && name(existingChild) == newIndexableObject.name =>
            ro
        }
      }

      def sameSide[L,R](a: \/[L, R], b: \/[L,R]): Boolean = (a.isLeft && b.isLeft) || (a.isRight && b.isRight)

      // step 1, add any newChildren.value to the existingChildren where they match
      val updatedExistingChildren: Seq[IndexableAttributeOrObject] = existingChildren.map{ existingChild =>
        val matchingNewValues: IndexableValues = getMatchingNewChildren(existingChild).flatMap(_.fold(_.values, _.values))
        existingChild
          .map(existingObj => existingObj.copy(values = existingObj.values ++ matchingNewValues))
          .leftMap(existingAttr => existingAttr.copy(values = existingAttr.values ++ matchingNewValues))
      }

      // step 2, add any newChildren which don't have existingChildren matches
      val nonExistingNewChildren = newChildren.filter(newChild =>
        existingChildren.find(existingChild =>
          sameSide(newChild, existingChild) && name(newChild) == name(existingChild)).empty
      )

      updatedExistingChildren ++ nonExistingNewChildren
    }

    // find any PartialRootObjects which *may* have objects or attributes that match this element or attribute
    val ofInterest = processing
      .filterKeys(path.startsWith(_))

    // update any PartialRootObjects children which match this element or attribute
    for (
      (rootObjectNodePath, partialRootObjects) <- ofInterest;
      partialRootObject <- partialRootObjects
    ) {
      val attributesConfig = partialRootObject.config.getAttribute.asScala
        .filter(attrConf => rootObjectNodePath.appendNew(nodePath(ns, attrConf.getPath)).equals(path))
      val attributes: Seq[IndexableAttribute] = attributesConfig.map(attrConfig => IndexableAttribute(attrConfig.getName, Seq(IndexableValue(nodeIdStr(node), node.map(_.toInMemory(broker)))), typeOrDefault(attrConfig.getType)))

      val objectsConfig = partialRootObject.config.getObject.asScala
        .filter(obj => rootObjectNodePath.appendNew(nodePath(ns, obj.getPath)).equals(path))
      val objects: Seq[IndexableObject] = objectsConfig.map(objectConfig => IndexableObject(objectConfig.getName, Seq(IndexableValue(nodeIdStr(node), node.map(_.toInMemory(broker)))), getObjectMappings(objectConfig)))

      if(attributes.nonEmpty || objects.nonEmpty) {
        val newChildren : Seq[IndexableAttribute \/ IndexableObject] = mergeIndexableChildren(partialRootObject.indexable.children, objects.map(_.right) ++ attributes.map(_.left))
        val newPartialRootObject = partialRootObject.copy(indexable = partialRootObject.indexable.copy(children = newChildren))
        val newPartialRootObjects = this.processing(rootObjectNodePath).filterNot(_ == partialRootObject) :+ newPartialRootObject

        this.processing = this.processing + (rootObjectNodePath -> newPartialRootObjects)
      }
    }
  }

  private def getObjectMappings(objectConfig: org.exist_db.collection_config._1.Object): Map[NodePath, (LiteralTypeConfig.LiteralTypeConfig, Option[Name])] = objectConfig.getMapping.asScala.map(mapping => nodePath(ns, mapping.getPath) -> (typeOrDefault(mapping.getType), Option(mapping.getName))).toMap

  private def getRootObjectConfigs(filter: RootObject => Boolean): Seq[(IndexName, RootObject)] = rootObjectConfigs.filter { case (_, rootObject) => filter(rootObject) }

  private def startIndexDocument(indexName: String, collectionId: CollectionId, documentId: DocumentId) {
    incrementalIndexingActor ! StartDocument(indexName, collectionId, documentId)
  }

  //INDEX IT!
  private def index(indexName: IndexName, userSpecifiedDocumentId: Option[String], indexableRootObject: IndexableRootObject) {
    incrementalIndexingActor ! Add(indexName, userSpecifiedDocumentId, indexableRootObject)


//    indexWorker.getIndex.getAuthentication match {
//      case None =>
//        LOG.error("Cannot add object to Algolia index, no Authentication credentials provided")
//
//      case Some(auth) =>
//        val client = new AsyncHttpAPIClientBuilder(auth.applicationId, auth.adminApiKey)
//          .build()
//
//        val index = indexes.get(indexName) match {
//          case Some(idx) => idx
//          case None =>
//            val idx = client.initIndex(indexName, classOf[IndexableRootObject])
//            indexes += (indexName -> idx)
//            idx
//        }
//
//        //TODO(AR) batch up objects
//        val futIndexedObject = index.addObject(indexableRootObject)
//
//        //TODO(AR) make async... .get will Await
//        val indexedId = futIndexedObject.get().getObjectID
//        if(LOG.isTraceEnabled()) {
//          LOG.trace("Indexed: {}", indexedId)
//        }
//    }
  }

  private def finishDocumentIndex(indexName: IndexName, userSpecifiedDocumentId: Option[String], collectionId: CollectionId, documentId: DocumentId) {
    incrementalIndexingActor ! FinishDocument(indexName, userSpecifiedDocumentId, collectionId, documentId)
  }
}
