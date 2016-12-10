package org.humanistika.exist.index.algolia

import java.io.{ByteArrayInputStream, StringWriter}
import java.nio.charset.StandardCharsets
import javax.xml.parsers.DocumentBuilderFactory

import com.fasterxml.jackson.databind.ObjectMapper
import AlgoliaStreamListener._
import org.specs2.Specification
import org.w3c.dom.{Attr, Document, Element, Node}

import scalaz._
import Scalaz._

/**
  * Created by aretter on 04/12/2016.
  */
class IndexableRootObjectJsonSerializerSpec extends Specification { def is = s2"""
  This is a specification to check the JSON Serialization of IndexableRootObject

    The basic JSON serialized result must
      have a document id $e1
      have a nodeId (if provided) $e2

    The JSON serialized result attributes for DOM Attributes must
      be constructable $e3
      be float convertible $e4
      be int convertible $e5
      be boolean convertible $e6
      allow multiple $e7

    The JSON serialized result attributes for DOM Elements must
      be constructable $e8
      be float convertible $e9
      be int convertible $e10
      be boolean convertible $e11
      allow multiple $e12
      serialize all text nodes $e13

    The JSON serialized result objects for DOM Attributes must
      be the same as a result attribute $e14

    The JSON serialized result objects for DOM Elements must
      be constructable $e15
      write nested elements $e16
      write array $e17
      write nested array $e18

  """

  def e1 = {
    val indexableRootObject = new IndexableRootObject(5, 46, None, Seq.empty)
    serializeJson(indexableRootObject) mustEqual """{"objectID":"5/46/0"}"""
  }

  def e2 = {
    val indexableRootObject = new IndexableRootObject(6, 47, Some("1.2.2"), Seq.empty)
    serializeJson(indexableRootObject) mustEqual """{"objectID":"6/47/1.2.2"}"""
  }

  def e3 = {
    val attr1 = attr(dom("""<w value="hello"/>"""), "value")
    val attributes = Seq(-\/(IndexableAttribute("attr1", "1.1", \/-(attr1), LiteralTypeConfig.String)))
    val indexableRootObject = new IndexableRootObject(7, 48, Some("1"), attributes)
    serializeJson(indexableRootObject) mustEqual """{"objectID":"7/48/1","attr1":"hello"}"""
  }

  def e4 = {
    val attr1 = attr(dom("""<x value="99.9"/>"""), "value")
    val attributes = Seq(-\/(IndexableAttribute("attr1", "1.1", \/-(attr1), LiteralTypeConfig.Float)))
    val indexableRootObject = new IndexableRootObject(2, 48, Some("1"), attributes)
    serializeJson(indexableRootObject) mustEqual """{"objectID":"2/48/1","attr1":99.9}"""
  }

  def e5 = {
    val attr1 = attr(dom("""<y value="1012"/>"""), "value")
    val attributes = Seq(-\/(IndexableAttribute("attr1", "1.1", \/-(attr1), LiteralTypeConfig.Integer)))
    val indexableRootObject = new IndexableRootObject(9, 48, Some("1"), attributes)
    serializeJson(indexableRootObject) mustEqual """{"objectID":"9/48/1","attr1":1012}"""
  }

  def e6 = {
    val attr1 = attr(dom("""<z value="true"/>"""), "value")
    val attributes = Seq(-\/(IndexableAttribute("attr1", "1.1", \/-(attr1), LiteralTypeConfig.Boolean)))
    val indexableRootObject = new IndexableRootObject(3, 48, Some("1"), attributes)
    serializeJson(indexableRootObject) mustEqual """{"objectID":"3/48/1","attr1":true}"""
  }

  def e7 = {
    val dom1 = dom("""<pos x="99.9" y="11.4"/>""")
    val attr1 = attr(dom1, "x")
    val attr2 = attr(dom1, "y")
    val attributes = Seq(-\/(IndexableAttribute("attr1", "1.1", \/-(attr1), LiteralTypeConfig.Float)), -\/(IndexableAttribute("attr2", "1.2", \/-(attr2), LiteralTypeConfig.Float)))
    val indexableRootObject = new IndexableRootObject(3, 48, Some("1"), attributes)
    serializeJson(indexableRootObject) mustEqual """{"objectID":"3/48/1","attr1":99.9,"attr2":11.4}"""
  }

  def e8 = {
    val elem1 = elem(dom("""<w>hello</w>"""), "w")
    val attributes = Seq(-\/(IndexableAttribute("elem1", "1.1", -\/(elem1), LiteralTypeConfig.String)))
    val indexableRootObject = new IndexableRootObject(6, 48, Some("1"), attributes)
    serializeJson(indexableRootObject) mustEqual """{"objectID":"6/48/1","elem1":"hello"}"""
  }

  def e9 = {
    val elem1 = elem(dom("""<x>99.9</x>"""), "x")
    val attributes = Seq(-\/(IndexableAttribute("elem1", "1.1", -\/(elem1), LiteralTypeConfig.Float)))
    val indexableRootObject = new IndexableRootObject(7, 48, Some("1"), attributes)
    serializeJson(indexableRootObject) mustEqual """{"objectID":"7/48/1","elem1":99.9}"""
  }

  def e10 = {
    val elem1 = elem(dom("""<y>1012</y>"""), "y")
    val attributes = Seq(-\/(IndexableAttribute("elem1", "1.1", -\/(elem1), LiteralTypeConfig.Integer)))
    val indexableRootObject = new IndexableRootObject(2, 48, Some("1"), attributes)
    serializeJson(indexableRootObject) mustEqual """{"objectID":"2/48/1","elem1":1012}"""
  }

  def e11 = {
    val elem1 = elem(dom("""<z>true</z>"""), "z")
    val attributes = Seq(-\/(IndexableAttribute("elem1", "1.1", -\/(elem1), LiteralTypeConfig.Boolean)))
    val indexableRootObject = new IndexableRootObject(1, 48, Some("1"), attributes)
    serializeJson(indexableRootObject) mustEqual """{"objectID":"1/48/1","elem1":true}"""
  }

  def e12 = {
    val elem1 = elem(dom("""<x>99.9</x>"""), "x")
    val elem2 = elem(dom("""<y>11.3</y>"""), "y")
    val attributes = Seq(-\/(IndexableAttribute("elem1", "1.1", -\/(elem1), LiteralTypeConfig.Float)), -\/(IndexableAttribute("elem2", "1.2", -\/(elem2), LiteralTypeConfig.Float)))
    val indexableRootObject = new IndexableRootObject(7, 48, Some("1"), attributes)
    serializeJson(indexableRootObject) mustEqual """{"objectID":"7/48/1","elem1":99.9,"elem2":11.3}"""
  }

  def e13 = {
    val elem1 = elem(dom("""<x>hello <b>world</b></x>"""), "x")
    val attributes = Seq(-\/(IndexableAttribute("elem1", "1.1", -\/(elem1), LiteralTypeConfig.String)))
    val indexableRootObject = new IndexableRootObject(23, 48, Some("1"), attributes)
    serializeJson(indexableRootObject) mustEqual """{"objectID":"23/48/1","elem1":"hello world"}"""
  }

  def e14 = {
    val attr1 = attr(dom("""<w value="hello"/>"""), "value")
    val objects = Seq(\/-(IndexableObject("obj1", "1.1", \/-(attr1), Map.empty)))
    val indexableRootObject = new IndexableRootObject(45, 48, Some("1"), objects)
    serializeJson(indexableRootObject) mustEqual """{"objectID":"45/48/1","obj1":"hello"}"""
  }

  def e15 = {
    val elem1 = elem(dom("""<w><x>hello</x><y>world</y></w>"""), "w")
    val objects = Seq(\/-(IndexableObject("obj1", "1.1", -\/(elem1), Map.empty)))
    val indexableRootObject = new IndexableRootObject(5, 48, Some("1"), objects)
    serializeJson(indexableRootObject) mustEqual """{"objectID":"5/48/1","obj1":{"nodeId":"1.1","x":"hello","y":"world"}}"""
  }

  def e16 = {
    val elem1 = elem(dom("""<w><x>hello</x><y><z>world</z><zz>again</zz></y></w>"""), "w")
    val objects = Seq(\/-(IndexableObject("obj1", "1.1", -\/(elem1), Map.empty)))
    val indexableRootObject = new IndexableRootObject(2, 48, Some("1"), objects)
    serializeJson(indexableRootObject) mustEqual """{"objectID":"2/48/1","obj1":{"nodeId":"1.1","x":"hello","y":{"z":"world","zz":"again"}}}"""
  }

  def e17 = {
    val elem1 = elem(dom("""<w><x>hello</x><y>world</y><y>again</y></w>"""), "w")
    val objects = Seq(\/-(IndexableObject("obj1", "1.1", -\/(elem1), Map.empty)))
    val indexableRootObject = new IndexableRootObject(3, 48, Some("1"), objects)
    serializeJson(indexableRootObject) mustEqual """{"objectID":"3/48/1","obj1":{"nodeId":"1.1","x":"hello","y":["world","again"]}}"""
  }

  def e18 = {
    val elem1 = elem(dom("""<w><x>hello</x><y><yy>world</yy><yy>again</yy></y></w>"""), "w")
    val objects = Seq(\/-(IndexableObject("obj1", "1.1", -\/(elem1), Map.empty)))
    val indexableRootObject = new IndexableRootObject(6, 48, Some("1"), objects)
    serializeJson(indexableRootObject) mustEqual """{"objectID":"6/48/1","obj1":{"nodeId":"1.1","x":"hello","y":{"yy":["world","again"]}}}"""
  }

  private def serializeJson(indexableRootObject: IndexableRootObject): String = {
    val writer = new StringWriter
    try {
      val mapper = new ObjectMapper
      mapper.writeValue(writer, indexableRootObject)
      val json = writer.toString
      return json
    } finally {
      writer.close()
    }
  }

  private lazy val documentBuilderFactory = DocumentBuilderFactory.newInstance()
  private def dom(xml: String) : Document = {
    val documentBuilder = documentBuilderFactory.newDocumentBuilder()
    val is = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))
    try {
      return documentBuilder.parse(is)
    } finally {
      is.close()
    }
  }

  private def attr(node: Node, name: String) : Attr = {
    if(node.isInstanceOf[Element]) {
      node.asInstanceOf[Element].getAttributeNode(name)
    } else if(node.isInstanceOf[Attr]) {
      node.asInstanceOf[Attr]
    } else if(node.isInstanceOf[Document]) {
      attr(node.asInstanceOf[Document].getDocumentElement, name)
    } else {
      throw new IllegalArgumentException
    }
  }

  private def elem(node: Node, name: String) : Element = {
    if(node.isInstanceOf[Element]) {
      val e = node.asInstanceOf[Element]
      if(Option(e.getLocalName).getOrElse(e.getNodeName) == name) {
        return e
      } else {
        val next = e.getNextSibling
        if(next != null) {
          return elem(next, name)
        }
      }
    } else if(node.isInstanceOf[Document]) {
      return elem(node.asInstanceOf[Document].getDocumentElement, name)
    }

    throw new IllegalArgumentException
  }

}
