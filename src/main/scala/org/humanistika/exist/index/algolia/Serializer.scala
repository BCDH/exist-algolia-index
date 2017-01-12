package org.humanistika.exist.index.algolia

import java.io.StringWriter
import java.util.Properties
import javax.xml.transform.{OutputKeys, TransformerFactory}
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.sax.SAXResult

import org.exist.storage.NodePath
import org.exist.util.serializer.{SAXSerializer, SerializerPool}
import org.humanistika.exist.index.algolia.LiteralTypeConfig.LiteralTypeConfig
import org.w3c.dom.{Element, Node}
import resource._

import scalaz._
import Scalaz._

object Serializer {

  private lazy val serializerPool = SerializerPool.getInstance
  private lazy val transformerFactory: TransformerFactory = new net.sf.saxon.TransformerFactoryImpl

  def serializeAsText(node: Node): Seq[Throwable] \/ String = {
    val properties = new Properties()
    properties.setProperty(OutputKeys.METHOD, "text")
    serialize(node, properties)
  }

  def serializeAsJson(element: Element, typeMappings: Map[NodePath, (LiteralTypeConfig, Option[String])]): Seq[Throwable] \/ String = {
    //TODO(AR) need to set the type/name mappings
    val properties = new Properties()
    properties.setProperty(OutputKeys.METHOD, "json")
    properties.setProperty(OutputKeys.INDENT, "no")
    serialize(element, properties)
  }

  def serialize(node: Node, properties: Properties): Seq[Throwable] \/ String = {
    makeManagedResource(serializerPool.borrowObject(classOf[SAXSerializer]).asInstanceOf[SAXSerializer])(serializerPool.returnObject)(List(classOf[IllegalStateException], classOf[NoSuchElementException], classOf[Exception]))
        .and(managed(new StringWriter())).map {
      case (handler, writer) =>
        handler.setOutput(writer, properties)

        val transformer = transformerFactory.newTransformer()
        val result = new SAXResult(handler)
        transformer.transform(new DOMSource(node), result)
        writer.toString
    }.either.either.disjunction
  }
}
