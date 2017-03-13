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

import java.util.{ArrayDeque, Deque}
import java.util.{Properties, HashMap => JHashMap, Map => JMap}
import javax.xml.XMLConstants
import javax.xml.namespace.QName

import org.exist.dom.persistent.{AttrImpl, ElementImpl, NamedNode, NodeProxy}
import org.exist.indexing.AbstractStreamListener
import org.exist.storage.{DBBroker, NodePath}
import org.exist.storage.txn.Txn
import AlgoliaStreamListener._
import org.exist.dom.memtree.{DocumentBuilderReceiver, MemTreeBuilder}
import org.exist_db.collection_config._1.{Algolia, LiteralType, RootObject}
import org.exist_db.collection_config._1.LiteralType._
import Serializer._
import akka.actor.{ActorPath, ActorSystem}
import grizzled.slf4j.Logger
import org.exist.indexing.StreamListener.ReindexMode
import org.humanistika.exist.index.algolia.NodePathWithPredicates.{AtomicEqualsComparison, AtomicNotEqualsComparison, SequenceEqualsComparison}
import org.humanistika.exist.index.algolia.backend.IncrementalIndexingManagerActor
import org.humanistika.exist.index.algolia.backend.IncrementalIndexingManagerActor.{Add, FinishDocument, RemoveForDocument, StartDocument}

import scala.collection.JavaConverters._
import scalaz._
import Scalaz._
import scala.annotation.tailrec


object AlgoliaStreamListener {

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

    /**
      * Creates a new NodePath which is equivalent
      * to the current path with the last component removed
      */
    def dropLastNew() : NodePath = {
      val result = nodePath.duplicate
      result.removeLastComponent()
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

  case class UserSpecifiedDocumentPathId(path: NodePath, value: Option[UserSpecifiedDocumentId])

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

  private val logger = Logger(classOf[AlgoliaStreamListener])

  private val incrementalIndexingActor = system.actorSelection(ActorPath.fromString(s"akka://${AlgoliaIndex.SYSTEM_NAME}/user/${IncrementalIndexingManagerActor.ACTOR_NAME}"))

  private val ns: JMap[String, String] = new JHashMap
  private var indexConfigs: Map[IndexName, org.exist_db.collection_config._1.Index] = Map.empty
  private var rootObjectConfigs: Seq[(IndexName, RootObject)] = Seq.empty

  private var replacingDocument: Boolean = false
  private var processing: Map[NodePath, Seq[PartialRootObject]] = Map.empty
  private var userSpecifiedDocumentIds: Map[IndexName, UserSpecifiedDocumentPathId] = Map.empty
  private var userSpecifiedNodeIds: Map[(IndexName, NodePath), Option[UserSpecifiedNodeId]] = Map.empty

  case class NameAndPredicates(name: QName, attributes: Map[QName, String])
  private val context: Deque[NameAndPredicates] = new ArrayDeque[NameAndPredicates]()

  def configure(config: Algolia) {
    this.ns.clear()
    getNamespaceMappings(config).foreach { case (k, v) => ns.put(k, v) }
    this.rootObjectConfigs = config.getIndex.asScala.flatMap(index => index.getRootObject.asScala.map(rootObject => (index.getName, rootObject)))
    this.indexConfigs = config.getIndex.asScala.map(index => index.getName -> index).toMap
  }

  override def getWorker: AlgoliaIndexWorker = indexWorker

  override def startReplaceDocument(transaction: Txn) {
    this.replacingDocument = true
  }

  override def startIndexDocument(transaction: Txn) {
    // find any User Specified Document IDs that we need to complete
    this.userSpecifiedDocumentIds = indexConfigs
      .map{ case (indexName, index) => indexName -> Option(index.getDocumentId).map(path => UserSpecifiedDocumentPathId(nodePath(ns, path), None)) }
      .collect{ case (indexName, Some(usdid)) => indexName -> usdid }

    getWorker.getMode() match {
      case ReindexMode.STORE =>
        startIndexDocumentForStore()

      case _ => // do nothing
    }

    super.startIndexDocument(transaction)
  }

  override def startElement(transaction: Txn, element: ElementImpl, path: NodePath) {
    val pathClone = path.duplicate

    // update the current context
    context.push(NameAndPredicates(element.getQName.toJavaQName, Map.empty))

    // update any userSpecifiedDocumentIds which we haven't yet completed and that match this element path
    for ((indexName, usdid) <- userSpecifiedDocumentIds if usdid.value.isEmpty && usdid.path.equals(pathClone)) {
      getString(element.left) match {
        case \/-(elementText) =>
          this.userSpecifiedDocumentIds = userSpecifiedDocumentIds + (indexName -> usdid.copy(value = Some(elementText)))
        case -\/(ts) =>
          logger.error(s"Unable to serialize element docId=${element.getOwnerDocument.getDocId} nodeId=${element.getNodeId.toString}", ts)
      }
    }

    getWorker.getMode() match {
      case ReindexMode.STORE =>
        startElementForStore(transaction, element, pathClone)

      case _ => // do nothing
    }

    super.startElement(transaction, element, path)
  }

  override def attribute(transaction: Txn, attrib: AttrImpl, path: NodePath) {
    val pathClone = path.duplicate
    pathClone.addComponent(attrib.getQName)

    // update the current context
    val elemContext = context.pop
    val newAttributes = elemContext.attributes + (attrib.getQName.toJavaQName -> attrib.getValue)
    context.push(elemContext.copy(attributes = newAttributes))

    // update any userSpecifiedDocumentIds which we haven't yet completed and that match this element path
    for ((indexName, usdid) <- userSpecifiedDocumentIds if usdid.value.isEmpty && usdid.path.equals(pathClone)) {
      getString(attrib.right) match {
        case \/-(attribValue) =>
          this.userSpecifiedDocumentIds = userSpecifiedDocumentIds + (indexName -> usdid.copy(value = Some(attribValue)))
        case -\/(ts) =>
          logger.error(s"Unable to serialize attribute docId=${attrib.getOwnerDocument.getDocId} nodeId=${attrib.getNodeId.toString}", ts)
      }
    }

    getWorker.getMode() match {
      case ReindexMode.STORE =>
        // update any PartialRootObjects children which match this attribute
        updateProcessingChildren(pathClone, attrib.right)

        // update any user defined nodes ids which match this attribute
        for (((indexName, nodeIdPath), usnid) <- userSpecifiedNodeIds if usnid.isEmpty && nodeIdPath.equals(pathClone)) {   //TODO(AR) do we need to compare the index name?
          getString(attrib.right) match {
            case \/-(attribValue) =>
              this.userSpecifiedNodeIds = userSpecifiedNodeIds + ((indexName, nodeIdPath) -> Some(attribValue))
            case -\/(ts) =>
              logger.error(s"Unable to serialize attribute docId=${attrib.getOwnerDocument.getDocId} nodeId=${attrib.getNodeId.toString}", ts)
          }
        }

      case _ => // do nothing
    }

    super.attribute(transaction, attrib, pathClone)
  }

  override def endElement(transaction: Txn, element: ElementImpl, path: NodePath) {
    val pathClone = path.duplicate

    getWorker.getMode() match {
      case ReindexMode.STORE =>
        endElementForStore(transaction, element, pathClone)

      case _ => // do nothing
    }

    // update the current context
    context.pop()

    super.endElement(transaction, element, path)
  }

  override def endIndexDocument(transaction: Txn) {
    getWorker.getMode() match {
      case ReindexMode.STORE =>
        endIndexDocumentForStore()

      case ReindexMode.REMOVE_ALL_NODES if(!replacingDocument) =>
        removeForDocument()

      case _ => // do nothing
    }

    // finished... so clear the map of things we are processing
    this.processing = Map.empty

    // clear any User Specified Document IDs
    this.userSpecifiedDocumentIds = Map.empty

    this.context.clear()

    super.endIndexDocument(transaction)
  }

  override def endReplaceDocument(transaction: Txn) {
    this.replacingDocument = false
  }

  private def removeForDocument() = {
    val docId = getWorker.getDocument.getDocId
    for(indexName <- indexConfigs.keys) {
      incrementalIndexingActor ! RemoveForDocument(indexName, docId, userSpecifiedDocumentIds.get(indexName).flatMap(_.value))
    }
  }

  private def startIndexDocumentForStore() {
    // start indexing any documents for which we have IndexableRootObjects
    indexConfigs.keys.foreach(indexName => startIndexDocument(indexName, indexWorker.getDocument.getCollection.getId, indexWorker.getDocument.getDocId))

    // find any RootObjects that we should start processing
    val documentRootObjects = getRootObjectConfigs(isDocumentRootObject)

    if (documentRootObjects.nonEmpty) {
      // as we are just starting a document,
      // we aren't processing these yet, so let's record them
      val processingAtPath = documentRootObjects.map(rootObjectConfig => PartialRootObject(rootObjectConfig._1, rootObjectConfig._2, IndexableRootObject(indexWorker.getDocument.getCollection.getURI.getCollectionPath, indexWorker.getDocument().getCollection.getId, indexWorker.getDocument().getDocId, None, None, None, Seq.empty)))
      this.processing = processing + (DOCUMENT_NODE_PATH -> processingAtPath)
    }
  }

  private def startElementForStore(transaction: Txn, element: ElementImpl, pathClone: NodePath) {
    // find any new RootObjects that we should process for this path
    val elementRootObjects = getRootObjectConfigs(isElementRootObject(element, pathClone))
    if (elementRootObjects.nonEmpty) {

      // record the new RootObjects that we are processing
      val newElementRootObjects: Seq[PartialRootObject] = elementRootObjects.map(rootObjectConfig => PartialRootObject(rootObjectConfig._1, rootObjectConfig._2, IndexableRootObject(indexWorker.getDocument().getCollection.getURI.getCollectionPath, indexWorker.getDocument().getCollection.getId, indexWorker.getDocument().getDocId, None, Some(element.getNodeId.toString), None, Seq.empty)))
      val processingAtPath = processing.get(pathClone) match {
        case Some(existingElementRootObjects) =>
          // we filter out newElementRootObjects that are equivalent to elementRootObjects which we are already processing
          existingElementRootObjects ++ newElementRootObjects.filterNot(newElementRootObject => existingElementRootObjects.find(_.identityEquals(newElementRootObject)).empty)
        case None =>
          newElementRootObjects
      }
      this.processing = processing + (pathClone -> processingAtPath)

      // find any user specified node ids for these root objects that we will later need to complete
      val newUserSpecifiedNodeIdPaths = elementRootObjects
        .map(rootObjectConfig => Option((rootObjectConfig._1, nodePath(ns, rootObjectConfig._2.getNodeId()))))
        .flatten
        .map{ case (indexName, nodeIdPath) => (indexName, pathClone.appendNew(nodeIdPath))}
      this.userSpecifiedNodeIds = userSpecifiedNodeIds ++ newUserSpecifiedNodeIdPaths.map(idxPath => (idxPath, None))
    }
  }

  private def endElementForStore(transaction: Txn, element: ElementImpl, pathClone: NodePath) {
    // update any PartialRootObjects children which match this element
    updateProcessingChildren(pathClone, element.left)

    // find any new RootObjects that we should finish processing
    // they must match the nodePath and also have a userSpecifiedDocumentId
    // if configured to do so
    val elementRootObjects = processing.getOrElse(pathClone, Seq.empty)
      .filterNot(partialRootObject => userSpecifiedDocumentIds.get(partialRootObject.indexName).exists(_.value.isEmpty))
    if (elementRootObjects.nonEmpty) {
      // index them
      elementRootObjects
        .foreach(partialRootObject => index(partialRootObject.indexName, partialRootObject.indexable.copy(userSpecifiedDocumentId = getUserSpecifiedDocumentId(partialRootObject.indexName), userSpecifiedNodeId = getUserSpecifiedNodeId(partialRootObject.indexName, pathClone))))

      // finished... so remove them from the map of things we are processing
      this.processing = processing.filterKeys(_ != pathClone)

      val indexNames = elementRootObjects.map(partialRootObject => partialRootObject.indexName)
      this.userSpecifiedNodeIds = this.userSpecifiedNodeIds.filterKeys{ case (indexName, nodePath) => !(indexNames.contains(indexName) && nodePath.dropLastNew() == pathClone) }
    }
  }

  private def endIndexDocumentForStore() {
    // find any outstanding RootObjects that we should finish processing
    val documentRootObjects = processing.values.flatten
    if (documentRootObjects.nonEmpty) {
      // index them
      documentRootObjects
        .foreach(partialRootObject => index(partialRootObject.indexName, partialRootObject.indexable.copy(userSpecifiedDocumentId = getUserSpecifiedDocumentId(partialRootObject.indexName))))
    }

    // finish indexing any documents for which we have IndexableRootObjects
    indexConfigs.keys.foreach(indexName => finishDocumentIndex(indexName, userSpecifiedDocumentIds.get(indexName).flatMap(_.value), indexWorker.getDocument.getCollection.getId, indexWorker.getDocument.getDocId))

    // finished... so clear the map of things we are processing
    this.processing = Map.empty

    this.userSpecifiedDocumentIds = Map.empty
    this.userSpecifiedNodeIds = Map.empty
  }

  private def getUserSpecifiedDocumentId(indexName: IndexName) : Option[UserSpecifiedDocumentId] = {
    userSpecifiedDocumentIds
      .get(indexName)
      .flatMap(_.value)
  }

  private def getUserSpecifiedNodeId(indexName: IndexName, rootObjectPath: NodePath) : Option[UserSpecifiedNodeId] = {
    userSpecifiedNodeIds
      .keySet
      .filter{ case (name, path) => name == indexName && path.dropLastNew() == rootObjectPath }
      .headOption
      .flatMap(userSpecifiedNodeIds.get(_))
      .flatten
  }

  private def isDocumentRootObject(rootObject: RootObject): Boolean = Option(rootObject.getPath).forall(path => path.isEmpty || path.equals("/"))

  private def isElementRootObject(currentNode: ElementImpl, path: NodePath)(rootObject: RootObject): Boolean = {
    // nodePath(ns, rootObject.getPath) == path

    val rootObjectPath = NodePathWithPredicates(ns.asScala.toMap, rootObject.getPath)
    if(rootObjectPath.asNodePath == path) {
      nodePathAndPredicatesMatch(currentNode)(rootObjectPath)
    } else {
      false
    }
  }

  // returns a rootObject only if the predicates on its nodePath hold
  private def nodePathAndPredicatesMatch(currentNode: NamedNode[_])(npwp: NodePathWithPredicates): Boolean = {
    val contextNodes: scala.collection.immutable.List[NameAndPredicates] = context.asScala.toList.reverse

    // TODO(AR) won't handle //* or /*
    // if contextNodes is empty then we have matched OK
    val unmatched = npwp.foldLeft(contextNodes){ case (cn, component) =>
        cn match {
          case Nil =>
            Nil
          case contextNode :: tail =>
            val contextNodeName = contextNode.name
            if(component.name.name == contextNodeName &&
                (currentNode.isInstanceOf[AttrImpl]
                  || (currentNode.isInstanceOf[ElementImpl] && predicatesMatch(contextNode.attributes)(component.predicates)))) {
              tail
            } else {
              contextNode :: tail
            }
        }
    }

    unmatched.isEmpty
  }

  private def predicatesMatch(contextAttributes: Map[QName, String])(predicates: Seq[NodePathWithPredicates.Predicate]): Boolean = {
    def predicateMatch(predicate: NodePathWithPredicates.Predicate): Boolean = {
      val attrName = predicate.left.name
      val predValue = predicate.right

      contextAttributes.get(attrName) match {
        case Some(attrValue) =>
          predicate.comparisonOperator match {
            case AtomicEqualsComparison if (predValue.size == 1) =>
              attrValue == predValue

            case AtomicNotEqualsComparison if (predValue.size == 1) =>
              attrValue != predValue

            case SequenceEqualsComparison if (!predValue.isEmpty) =>
              predValue.find(_ != attrValue).isEmpty

            case _ =>
              false
          }

        case None =>
          false
      }
    }

    // find the first predicate that does not match
    val firstMatchFailure = predicates.find(predicate => !predicateMatch(predicate))
    firstMatchFailure.isEmpty
  }

  private def getString(node: ElementOrAttributeImpl): Seq[Throwable] \/ String = {
    node match {
      case -\/(element) =>
        serializeAsText(element)
      case \/-(attribute) =>
        attribute.getValue.right
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

    def toInMemory(node: ElementOrAttributeImpl): org.exist.dom.memtree.ElementImpl \/ org.exist.dom.memtree.AttrImpl = node.bimap(_.toInMemory(broker), _.toInMemory(broker))

    def asNamedNode(node: ElementOrAttributeImpl) : NamedNode[_] = node.fold(_.asInstanceOf[NamedNode[ElementImpl]], _.asInstanceOf[NamedNode[AttrImpl]])

    def asNodePathWithPredicates(rootObjectPath: String, elemOrAttrPath: String) : NodePathWithPredicates = {
      val sep = if(rootObjectPath.endsWith("/") || elemOrAttrPath.startsWith("/")) {
        ""
      } else {
        "/"
      }
      NodePathWithPredicates(ns.asScala.toMap, rootObjectPath + sep + elemOrAttrPath)
    }

    // find any PartialRootObjects which *may* have objects or attributes that match this element or attribute
    val ofInterest = processing
      .filterKeys(path.startsWith(_))

    // update any PartialRootObjects children which match this element or attribute
    for (
      (rootObjectNodePath, partialRootObjects) <- ofInterest;
      partialRootObject <- partialRootObjects
    ) {

      //TODO(AR) filters for attributesConfig and objectsConfig need to check nodePathsWithPredicates

      val attributesConfig = partialRootObject.config.getAttribute.asScala
          .filter(attrConf => nodePathAndPredicatesMatch(asNamedNode(node))(asNodePathWithPredicates(partialRootObject.config.getPath, fixXjcAttrOutput(attrConf.getPath))))
      val attributes: Seq[IndexableAttribute] = attributesConfig.map(attrConfig => IndexableAttribute(attrConfig.getName, Seq(IndexableValue(nodeIdStr(node), toInMemory(node))), typeOrDefault(attrConfig.getType)))

      val objectsConfig = partialRootObject.config.getObject.asScala
        .filter(objConf => nodePathAndPredicatesMatch(asNamedNode(node))(asNodePathWithPredicates(partialRootObject.config.getPath, objConf.getPath)))
      val objects: Seq[IndexableObject] = objectsConfig.map(objectConfig => IndexableObject(objectConfig.getName, Seq(IndexableValue(nodeIdStr(node), toInMemory(node))), getObjectMappings(objectConfig)))

      if(attributes.nonEmpty || objects.nonEmpty) {
        val newChildren : Seq[IndexableAttribute \/ IndexableObject] = mergeIndexableChildren(partialRootObject.indexable.children, objects.map(_.right) ++ attributes.map(_.left))
        val newPartialRootObject = partialRootObject.copy(indexable = partialRootObject.indexable.copy(children = newChildren))
        val newPartialRootObjects = this.processing(rootObjectNodePath).filterNot(_ == partialRootObject) :+ newPartialRootObject

        this.processing = this.processing + (rootObjectNodePath -> newPartialRootObjects)
      }
    }
  }

  // see http://stackoverflow.com/questions/42656550/xjc-generating-wrong-liststring-for-xmlattribute
  private def fixXjcAttrOutput(attrList : java.util.List[String]) = attrList.asScala.mkString(" ")

  private def getObjectMappings(objectConfig: org.exist_db.collection_config._1.Object): Map[NodePath, (LiteralTypeConfig.LiteralTypeConfig, Option[Name])] = objectConfig.getMapping.asScala.map(mapping => nodePath(ns, fixXjcAttrOutput(mapping.getPath)) -> (typeOrDefault(mapping.getType), Option(mapping.getName))).toMap

  private def getRootObjectConfigs(filter: RootObject => Boolean): Seq[(IndexName, RootObject)] = rootObjectConfigs.filter { case (_, rootObject) => filter(rootObject) }

  private def startIndexDocument(indexName: String, collectionId: CollectionId, documentId: DocumentId) {
    incrementalIndexingActor ! StartDocument(indexName, collectionId, documentId)
  }

  //INDEX IT!
  private def index(indexName: IndexName, indexableRootObject: IndexableRootObject) {
    incrementalIndexingActor ! Add(indexName, indexableRootObject)
  }

  private def finishDocumentIndex(indexName: IndexName, userSpecifiedDocumentId: Option[String], collectionId: CollectionId, documentId: DocumentId) {
    incrementalIndexingActor ! FinishDocument(indexName, userSpecifiedDocumentId, collectionId, documentId)
  }
}
