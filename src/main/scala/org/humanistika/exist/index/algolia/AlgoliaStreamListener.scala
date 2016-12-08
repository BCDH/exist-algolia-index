package org.humanistika.exist.index.algolia

import java.util.{List => JList, Map => JMap, HashMap => JHashMap}
import javax.xml.namespace.QName

import org.exist.dom.persistent.{AttrImpl, ElementImpl}
import org.exist.indexing.AbstractStreamListener
import org.exist.storage.NodePath
import org.exist.storage.txn.Txn
import AlgoliaStreamListener._
import com.algolia.search.{AsyncHttpAPIClientBuilder, AsyncIndex}
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import org.apache.logging.log4j.{LogManager, Logger}
import org.exist.numbering.NodeId
import org.exist_db.collection_config._1.{Algolia, LiteralType, Mapping, RootObject}
import org.exist_db.collection_config._1.LiteralType._
import org.w3c.dom.{Attr, Element}

import scala.collection.JavaConverters._
import scalaz._
import Scalaz._


object AlgoliaStreamListener {

  private val LOG: Logger = LogManager.getLogger(classOf[AlgoliaStreamListener])

  object LiteralTypeConfig extends Enumeration {
    type LiteralTypeConfig = Value
    val String, Integer, Float, Boolean = Value
  }

  type IndexName = String
  type Name = String

  case class RootObjectConfig(id: Int, indexName: String)
  case class AttributeConfig(root: RootObjectConfig, name: Name, literalType: LiteralTypeConfig.LiteralTypeConfig)
  case class ObjectConfig(root: RootObjectConfig, name: Name, mappings: Seq[MappingConfig])
  case class MappingConfig(nodePath: NodePath, literalType: LiteralTypeConfig.LiteralTypeConfig, name: Option[String])

  type ConfigByPath = Map[NodePath, IndexConfig]
  type IndexConfig = Map[IndexName, Seq[AttributeConfig \/ ObjectConfig]]

  type ElementOrAttribute = Element \/ Attr

  @JsonSerialize(using=classOf[IndexableRootObjectJsonSerializer]) case class IndexableRootObject(documentId: Int, nodeId: Option[String], children: Seq[IndexableAttribute \/ IndexableObject])
  case class IndexableAttribute(name: Name, nodeId: String,  value: ElementOrAttribute, literalType: LiteralTypeConfig.LiteralTypeConfig)
  case class IndexableObject(name: Name, nodeId: String,  value: ElementOrAttribute, typeMappings: Map[NodePath, (LiteralTypeConfig.LiteralTypeConfig, Option[Name])])

  def typeOrDefault(literalType: LiteralType): LiteralTypeConfig.LiteralTypeConfig = {
    Option(literalType) match {
      case Some(INTEGER) =>
        LiteralTypeConfig.Integer
      case Some(FLOAT) =>
        LiteralTypeConfig.Float
      case Some(BOOLEAN) =>
        LiteralTypeConfig.Boolean
      case _ =>
        LiteralTypeConfig.String
    }
  }

  def nodePath(ns: JMap[String, String], path: String) : NodePath = {
    Option(path)
      .filterNot(_ == "/")
      .map(new NodePath(ns, _))
      .getOrElse(new NodePath())
  }

  /**
    * Creates a new NodePath which is equivalent
    * to the path of /a/b
    */
  def concat(a: NodePath, b: NodePath): NodePath = {
    val c = new NodePath(a)
    c.append(b)
    c
  }

  private def toMap[T](seq: Seq[(NodePath, T)]): Map[NodePath, Seq[T]] =
    seq.foldLeft(Map.empty[NodePath, Seq[T]]){ case (map, (nodePath, t)) =>
      map + (nodePath -> (map.get(nodePath).getOrElse(Seq.empty) :+ t))
    }

  /**
    * Merges two Maps of sequences
    */
  private def merge[K, V1, V2](a: Map[K, Seq[V1]], b: Map[K, Seq[V2]]): Map[K, Seq[V1 \/ V2]] = {
    val av : Map[K, Seq[V1 \/ V2]] = a.mapValues(_.map(_.left))
    val bv : Map[K, Seq[V1 \/ V2]] = b.mapValues(_.map(_.right))
    val it = for(key <- av.keys ++ bv.keys)
       yield (key, av.getOrElse(key, Seq.empty[V1 \/ V2]) ++ bv.getOrElse(key, Seq.empty[V1 \/ V2]))
    it.toMap
  }

  /**
    * Merges two Maps of sequences of disjunction
    */
  private def mergeV[K, V1, V2](a: Map[K, Seq[V1 \/ V2]], b: Map[K, Seq[V1 \/ V2]]): Map[K, Seq[V1 \/ V2]] = {
    val it = for(key <- a.keys ++ b.keys)
      yield (key, a.getOrElse(key, Seq.empty[V1 \/ V2]) ++ b.getOrElse(key, Seq.empty[V1 \/ V2]))
    it.toMap
  }

  /**
    * Merges two Maps of Maps of sequences of disjunction
    */
  private def mergeMV[K, KK, V1, V2](a: Map[K, Map[KK, Seq[V1 \/ V2]]], b: Map[K, Map[KK, Seq[V1 \/ V2]]]): Map[K, Map[KK, Seq[V1 \/ V2]]] = {
    val it = for (key <- a.keys ++ b.keys)
      yield (key -> mergeV(a.getOrElse(key, Map.empty), b.getOrElse(key, Map.empty)))
    it.toMap
  }

  private def getNamespaceMappings(config: Algolia) : Map[String, String] = {
    Option(config.getNamespaceMappings)
      .map(_.getNamespaceMapping.asScala.map(nsm => nsm.getPrefix -> nsm.getNamespace).toMap)
      .getOrElse(Map.empty[String, String])
  }

  private def byPath(ns: JMap[String, String], config: Algolia): ConfigByPath = {
    def getAttributeConfigs(ns: JMap[String, String], rootObject: RootObject, rootObjectIdx: Int, rootPath: NodePath, indexName: String):  Map[NodePath, Seq[AttributeConfig]] = {
      toMap(rootObject.getAttribute.asScala.map { attribute =>
        val attributePath = concat(rootPath, nodePath(ns, attribute.getPath))
        val config = AttributeConfig(RootObjectConfig(rootObjectIdx, indexName), attribute.getName, typeOrDefault(attribute.getType))
        (attributePath -> config)
      })
    }
    def getObjectConfigs(ns: JMap[String, String], rootObject: RootObject, rootObjectIdx: Int, rootPath: NodePath, indexName: String):  Map[NodePath, Seq[ObjectConfig]] = {
      toMap(rootObject.getObject.asScala.map { subObject =>
        def mappings(subObjectPath: NodePath, mps: Seq[Mapping]): Seq[MappingConfig] = {
          mps.map(mp => MappingConfig(concat(subObjectPath, nodePath(ns, mp.getPath)), typeOrDefault(mp.getType), Option(mp.getName)))
        }

        val subObjectPath = concat(rootPath, nodePath(ns, subObject.getPath))
        val config = ObjectConfig(RootObjectConfig(rootObjectIdx, indexName), subObject.getName, mappings(subObjectPath, subObject.getMapping.asScala))
        (subObjectPath -> config)
      })
    }

    config.getIndex.asScala.map { index =>
      val indexName = index.getName

      val configsPerIndex: Map[NodePath, Seq[AttributeConfig \/ ObjectConfig]] = index.getRootObject.asScala.zipWithIndex.map { case (rootObject, rootObjectIdx) =>
        val rootPath = Option(rootObject.getPath).map(nodePath(ns, _)).getOrElse(new NodePath())
        val attrs = getAttributeConfigs(ns, rootObject, rootObjectIdx, rootPath, indexName)
        val objs = getObjectConfigs(ns, rootObject, rootObjectIdx, rootPath, indexName)

        merge(attrs, objs)

      }.reduceLeft(mergeV(_, _))

      configsPerIndex.map{ case (k,v) => (k -> Map(indexName -> v))}

    }.reduceLeft(mergeMV(_, _))
  }
}

class AlgoliaStreamListener(indexWorker: AlgoliaIndexWorker) extends AbstractStreamListener {

  private var allConfigs: ConfigByPath = Map.empty
  private val ns: JMap[String, String] = new JHashMap

  def configure(config: Algolia) {
    this.ns.clear()
    getNamespaceMappings(config).foreach{ case (k,v) => ns.put(k, v)}

    this.allConfigs = byPath(ns, config)
  }

//  private var processingConfigs : ConfigByPath = Map.empty[NodePath, IndexConfig]
  private var processingNodes: Map[NodePath, ElementImpl \/ AttrImpl] = Map.empty[NodePath, ElementImpl \/ AttrImpl]

  override def getWorker = indexWorker

  override def startElement(transaction: Txn, element: ElementImpl, path: NodePath) {
    val pathClone = new NodePath(path)

    // store which configs are relevant for this element
//    this.processingConfigs = allConfigs.get(pathClone) match {
//      case Some(config) =>
//        val processingConfig: IndexConfig = mergeV(processingConfigs.getOrElse(path, Map.empty), config)
//        mergeMV(processingConfigs, Map(pathClone -> processingConfig))
//      case None =>
//        processingConfigs
//    }

    if(allConfigs.contains(pathClone)) {
      processingNodes = processingNodes + (pathClone -> element.left)

      //TODO(AR) record the rootObject(s) for the element in the processingNodes too
    }

    //TODO(AR) associate the element with all processingConfigs
    //TODO(AR) in endElement, when we `finish` a rootObject, we can then just serialize all of its attributes and objects (or pass them to Another actor to be serialized)
    //TODO(AR) we also then need to clear out processingConfigs appropriately in endElement

    super.startElement(transaction, element, path)
  }

  //TODO(AR) can most likely really simplify allConfigs

  override def attribute(transaction: Txn, attrib: AttrImpl, path: NodePath) {
    val pathClone = new NodePath(path)

    if(allConfigs.contains(pathClone)) {
      processingNodes = processingNodes + (pathClone -> attrib.right)
    }
    super.attribute(transaction, attrib, pathClone)
  }

  def getRootObjects(filter: RootObject => Boolean) : Seq[(IndexName, RootObject)] = {
    indexWorker.getConfig
      .map(_.getIndex.asScala.flatMap(index => index.getRootObject.asScala.toSeq.map(rootObject => (index.getName, rootObject))))
      .getOrElse(Seq.empty)
      .filter{ case (_, rootObject) => filter(rootObject) }
  }

  override def endElement(transaction: Txn, element: ElementImpl, path: NodePath) {
    val pathClone = new NodePath(path)
    val finishingRootObjects = getRootObjects(rootObject => nodePath(ns, rootObject.getPath) == pathClone)
    finishingRootObjects
      .map { case (indexName, rootObject) => (indexName, toIndexable(rootObject, Some(element.getNodeId))) }
      .map { case (indexName, indexableRootObject) => index(indexName, indexableRootObject) }

    //TODO(AR) clean out the state - e.g. processingNodes and/or processingConfigs
  }

  override def endIndexDocument(transaction: Txn) {
    val finishingRootObjects = getRootObjects(rootObject => rootObject.getPath == "/" || Option(rootObject.getPath).isEmpty)
    finishingRootObjects
      .map { case (indexName, rootObject) => (indexName, toIndexable(rootObject)) }
      .map { case (indexName, indexableRootObject) => index(indexName, indexableRootObject) }

    //TODO(AR) clean out the state - e.g. processingNodes and/or processingConfigs
  }

  def toIndexable(rootObject: RootObject, nodeId: Option[NodeId] = None) : IndexableRootObject = {
    def getMappings(mappings: JList[Mapping]) : Map[NodePath, (LiteralTypeConfig.LiteralTypeConfig, Option[String])] = {
      mappings
        .asScala
        .map(mapping => (nodePath(ns, mapping.getPath) -> (typeOrDefault(mapping.getType), Option(mapping.getName))))
        .toMap
    }

    val attributes = rootObject.getAttribute.asScala.toSeq.map { attribute =>
      processingNodes.get(nodePath(ns, attribute.getPath)).map {
        case -\/(elem) =>
          IndexableAttribute(attribute.getName, elem.getNodeId.toString, elem.left, typeOrDefault(attribute.getType))
        case \/-(attr) =>
          IndexableAttribute(attribute.getName, attr.getNodeId.toString, attr.right, typeOrDefault(attribute.getType))
      }
    }.flatten

    val objects = rootObject.getObject.asScala.toSeq.map { obj =>
      processingNodes.get(nodePath(ns, obj.getPath)).map {
        case -\/(elem) =>
          IndexableObject(obj.getName, elem.getNodeId.toString, elem.left, getMappings(obj.getMapping))
        case \/-(attr) =>
          IndexableObject(obj.getName, attr.getNodeId.toString, attr.right, getMappings(obj.getMapping))
      }
    }.flatten

    IndexableRootObject(indexWorker.getDocument.getDocId, nodeId.map(_.toString), attributes.map(_.left) ++ objects.map(_.right))
  }

  type IndexName = String
  private var indexes: Map[IndexName, AsyncIndex[IndexableRootObject]] = Map.empty

  //INDEX IT!
  def index(indexName: IndexName, indexableRootObject: IndexableRootObject) {
    indexWorker.getIndex.getAuthentication match {
      case None =>
        LOG.error("Cannot add object to Algolia index, no Authentication credentials provided")

      case Some(auth) =>
        // recommended by Algolia
        java.security.Security.setProperty("networkaddress.cache.ttl", "60")

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
        println(futIndexedObject.get().getObjectID)
    }
  }





//
//  override def characters(transaction: Txn, text: AbstractCharacterData, path: NodePath) {
//    val selfOrAncestorConfigs : Seq[IndexConfig] = processingConfigs.filter(_._1.compareTo(path) > 0).values.toSeq
//
//    for(selfOrAncestorConfig <- selfOrAncestorConfigs) {
//      for(value <- selfOrAncestorConfig.values.flatten) {
//        value match {
//          case -\/(AttributeConfig(RootObjectConfig(id, indexName), name, literalType)) =>
//
//          case \/-(ObjectConfig(RootObjectConfig(id, indexName), name, mappings)) =>
//        }
//      }
//    }
//
//    super.characters(transaction, text, path)
//  }
//
//  override def endElement(transaction: Txn, element: ElementImpl, path: NodePath) {
//    val finishConfigs = processingConfigs.get(path)
//
//    //TODO publish the data for the configs that we have collected
//
//    // remove configs that are no longer relevant
//    this.processingConfigs = processingConfigs - path
//
//    super.endElement(transaction, element, path)
//  }
}

















//object AlgoliaStreamListener {
//
//  object LiteralType extends Enumeration {
//    type LiteralType = Value
//    val String, Integer, Float, Boolean = Value
//  }
//
//  type IndexName = String
//  type Name = String
//
//  case class RootObjectConfig(path: Option[NodePath], attributes: Seq[AttributeConfig], objects: Seq[ObjectConfig])
//  case class AttributeConfig(name: Name, path: NodePath, literalType: LiteralType.LiteralType = LiteralType.String)
//  case class ObjectConfig(name: Name, path: NodePath, mappings: Seq[MappingConfig])
//  case class MappingConfig(path: NodePath, literalType: LiteralType.LiteralType = LiteralType.String, name: Option[Name] = None)
//
//  case class IndexableRootObject(docId: Int, nodeId: Option[String] = None, attributes: Seq[IndexableAttribute] = Seq.empty, objects: Seq[IndexableObject] = Seq.empty)
//  case class IndexableAttribute(name: Name, value: String, literalType: LiteralType.LiteralType)
//  case class IndexableObject(name: Name, nodeId: String, mappings: Seq[IndexableObjectMapping])
//  case class IndexableObjectMapping(nodeId: String, literalType: LiteralType.LiteralType)
//}
//
////TODO(AR) each RootObjectConfig is a small state machine, which should be completed up until
//// we either reach the end of the document or the end of the path for a rootObject
//
//
//class AlgoliaStreamListener(indexWorker: AlgoliaIndexWorker) extends AbstractStreamListener {
//
//  private lazy val configuredIndexes : Option[Map[IndexName, Seq[RootObjectConfig]]] = indexWorker.getConfig.map(asConfiguredIndexes)
//  private val indexing = MutableMap.empty[IndexName, ListBuffer[(RootObjectConfig, ListBuffer[IndexableRootObject])]]
//
//  private def asConfiguredIndexes(indexConfig: Algolia) : Map[IndexName, Seq[RootObjectConfig]] = {
//    val ns : java.util.Map[String, String] = Option(indexConfig.getNamespaceMappings)
//        .map(_.getNamespaceMapping.asScala.map(nsm => nsm.getPrefix -> nsm.getNamespace).toMap)
//        .getOrElse(Map.empty[String, String])
//        .asJava
//
//    def literalType(literalType: Option[org.exist_db.collection_config._1.LiteralType]) : LiteralType.LiteralType = {
//      literalType match {
//        case Some(org.exist_db.collection_config._1.LiteralType.INTEGER) =>
//          LiteralType.Integer
//        case Some(org.exist_db.collection_config._1.LiteralType.FLOAT) =>
//          LiteralType.Float
//        case Some(org.exist_db.collection_config._1.LiteralType.BOOLEAN) =>
//          LiteralType.Boolean
//        case _ =>
//          LiteralType.String
//      }
//    }
//
//    def attributes(attributes: java.util.List[org.exist_db.collection_config._1.Attribute]) : Seq[AttributeConfig] = {
//      attributes.asScala.map(attribute => AttributeConfig(attribute.getName, new NodePath(ns, attribute.getPath), literalType(Option(attribute.getType))))
//    }
//
//    def subObjects(subObjects: java.util.List[org.exist_db.collection_config._1.Object]) = {
//      def mappings(mappings: java.util.List[org.exist_db.collection_config._1.Mapping]) : Seq[MappingConfig] = {
//        mappings.asScala.map(mapping => MappingConfig(new NodePath(ns, mapping.getPath), literalType(Option(mapping.getType)), Option(mapping.getName)))
//      }
//
//      subObjects.asScala.map(so => ObjectConfig(so.getName, new NodePath(ns, so.getPath), mappings(so.getMapping)))
//    }
//
//    indexConfig.getIndex.asScala.map { index =>
//      (index.getName ->
//        index.getRootObject.asScala.map(ro =>
//          RootObjectConfig(
//            Option(ro.getPath).map(new NodePath(ns, _)),
//            attributes(ro.getAttribute),
//            subObjects(ro.getObject)
//          )
//        )
//      )
//    }.toMap
//  }
//
//  implicit class MutableMapWithMerge[K, V](map: MutableMap[K, ListBuffer[V]]) {
//    def merge(other: Map[K, ListBuffer[V]]) : MutableMap[K, ListBuffer[V]] = {
//      for((k,v) <- other) {
//        map.get(k) match {
//          case Some(existing) =>
//            existing ++= v
//          case None =>
//            map.put(k, v)
//        }
//      }
//      map
//    }
//  }
//
//  override def getWorker = indexWorker
//
//  override def startIndexDocument(transaction: Txn) {
//    this.indexing.merge(matchRootObjectsForDocument)
//    super.startIndexDocument(transaction)
//  }
//
//  override def startElement(transaction: Txn, element: ElementImpl, path: NodePath) {
//    this.indexing.merge(matchRootObjectsForElement(path, element.getNodeId))
//
//    createIndexableAttributes(path)
//
//    super.startElement(transaction, element, path)
//  }
//
//
//  def createIndexableAttributes(path: NodePath) {
//
//  }
//
//
//  override def endElement(transaction: Txn, element: ElementImpl, path: NodePath) {
//
//
//    //TODO(AR) this isn't correct we need to filter and then dispatch the event to the actual indexer
//    this.indexing --= matchRootObjectsForElement(path).keySet
//
//    //TODO(AR) implement
//
//    super.endElement(transaction, element, path)
//  }
//
//
//  override def endIndexDocument(transaction: Txn) {
//
//    //TODO(AR) completed root objects
//
//    super.endIndexDocument(transaction)
//  }
//
//  override def characters(transaction: Txn, text: AbstractCharacterData, path: NodePath) {
//
//    //TODO(AR) implement
//
//    super.characters(transaction, text, path)
//  }
//
//  override def attribute(transaction: Txn, attrib: AttrImpl, path: NodePath) {
//
//    //TODO(AR) implement
//
//    super.attribute(transaction, attrib, path)
//  }
//
//  private def matchRootObjectsForDocument : Map[IndexName, Seq[(RootObjectConfig, Seq[IndexableRootObject])]] =
//    configuredIndexes.map(
//      _.filter(entry => entry._2.filter(_.path.isEmpty).nonEmpty)
//      .map{case (indexName, rootObjectConfigs) => (indexName -> rootObjectConfigs.map(rootObjectConfig => (rootObjectConfig, Seq(IndexableRootObject(indexWorker.getDocument.getDocId)))))}
//    ).getOrElse(Map.empty[IndexName, Seq[(RootObjectConfig, Seq[IndexableRootObject])]])
//
//  private def matchRootObjectsForElement(path: NodePath, nodeId: NodeId) : Map[IndexName, Seq[(RootObjectConfig, Seq[IndexableRootObject])]] = {
//    configuredIndexes.map(configuredIndex =>
//      configuredIndex.collect { case (indexName, rootObjects) =>
//        (indexName -> rootObjects.collect {
//          case ro@RootObjectConfig(Some(rootObjectNodePath), _, _) if (rootObjectNodePath.equals(path)) =>
//            (ro, Seq(IndexableRootObject(indexWorker.getDocument.getDocId, Some(nodeId.toString))))
//        })
//      }
//    ).getOrElse(Map.empty[IndexName, Seq[(RootObjectConfig, Seq[IndexableRootObject])]])
//  }
//
//}
