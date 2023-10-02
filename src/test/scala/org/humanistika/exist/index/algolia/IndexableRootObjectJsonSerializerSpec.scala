package org.humanistika.exist.index.algolia

import DOMHelper._

import java.io.StringWriter
import javax.xml.namespace.QName
import com.fasterxml.jackson.databind.ObjectMapper
import org.humanistika.exist.index.algolia.Serializer.{serializeElementForAttribute, serializeElementForObject}
import org.specs2.Specification
import cats.syntax.either._

import scala.util.Using

class IndexableRootObjectJsonSerializerSpec extends Specification { def is = s2"""
  This is a specification to check the JSON Serialization of IndexableRootObject

    The basic JSON serialized result must
      have a document id $e1
      prefer the user specified document id $e2
      have a nodeId (if provided) $e3
      prefer the user specified node id $e4

    The JSON serialized result attributes for DOM Attributes must
      be constructable $e5
      be float convertible $e6
      be int convertible $e7
      be boolean convertible $e8
      allow multiple $e9
      support arrays $e10

    The JSON serialized result attributes for DOM Elements must
      be constructable $e11
      be float convertible $e12
      be int convertible $e13
      be boolean convertible $e14
      allow multiple $e15
      serialize all text nodes $e16
      serialize all text nodes and not attributes $e17
      support arrays $e18
      support arrays (of text nodes and not attributes) $e19
      be valid when only child text nodes are provided $e20

    The JSON serialized result objects for DOM Attributes must
      be the same as a result attribute $e21
      support arrays $e22

    The JSON serialized result objects for DOM Elements must
      be constructable $e23
      write nested elements $e24
      write array $e25
      write nested array $e26
      support arrays $e27
      be valid when only child text nodes are provided $e28
      be valid when only attributes are provided $e29
      be valid when only child text nodes and attributes are provided $e30
"""

  def e1 = {
    val indexableRootObject = IndexableRootObject("/db/a1", 5, 46, None, None, None, Seq.empty)
    serializeJson(indexableRootObject) mustEqual """{"objectID":"5/46/0","collection":"/db/a1","documentID":46}"""
  }

  def e2 = {
    val indexableRootObject = IndexableRootObject("/db/a1", 5, 46, Some("my-document-id"), None, None, Seq.empty)
    serializeJson(indexableRootObject) mustEqual """{"objectID":"5/46/0","collection":"/db/a1","documentID":"my-document-id"}"""
  }

  def e3 = {
    val indexableRootObject = IndexableRootObject("/db/a1", 6, 47, None, Some("1.2.2"), None, Seq.empty)
    serializeJson(indexableRootObject) mustEqual """{"objectID":"6/47/1.2.2","collection":"/db/a1","documentID":47}"""
  }

  def e4 = {
    val indexableRootObject = IndexableRootObject("/db/a1", 5, 46, None, None, Some("my-node-id"), Seq.empty)
    serializeJson(indexableRootObject) mustEqual """{"objectID":"my-node-id","collection":"/db/a1","documentID":46}"""
  }

  def e5 = {
    val attr1_kv = new AttributeKV(new QName("value"), "hello")
    val attributes = Seq(Left(IndexableAttribute("attr1", Seq(IndexableValue("1.1", Right(attr1_kv))), LiteralTypeConfig.String)))
    val indexableRootObject = IndexableRootObject("/db/a1", 7, 48, None, Some("1"), None, attributes)
    serializeJson(indexableRootObject) mustEqual """{"objectID":"7/48/1","collection":"/db/a1","documentID":48,"attr1":"hello"}"""
  }

  def e6 = {
    val attr1_kv = new AttributeKV(new QName("value"), "99.9")
    val attributes = Seq(Left(IndexableAttribute("attr1", Seq(IndexableValue("1.1", Right(attr1_kv))), LiteralTypeConfig.Float)))
    val indexableRootObject = IndexableRootObject("/db/a1", 2, 49, None, Some("1"), None, attributes)
    serializeJson(indexableRootObject) mustEqual """{"objectID":"2/49/1","collection":"/db/a1","documentID":49,"attr1":99.9}"""
  }

  def e7 = {
    val attr1_kv = new AttributeKV(new QName("value"), "1012")
    val attributes = Seq(Left(IndexableAttribute("attr1", Seq(IndexableValue("1.1", Right(attr1_kv))), LiteralTypeConfig.Integer)))
    val indexableRootObject = IndexableRootObject("/db/a1", 9, 50, None, Some("1"), None, attributes)
    serializeJson(indexableRootObject) mustEqual """{"objectID":"9/50/1","collection":"/db/a1","documentID":50,"attr1":1012}"""
  }

  def e8 = {
    val attr1_kv = new AttributeKV(new QName("value"), "true")
    val attributes = Seq(Left(IndexableAttribute("attr1", Seq(IndexableValue("1.1", Right(attr1_kv))), LiteralTypeConfig.Boolean)))
    val indexableRootObject = IndexableRootObject("/db/a1", 3, 51, None, Some("1"), None, attributes)
    serializeJson(indexableRootObject) mustEqual """{"objectID":"3/51/1","collection":"/db/a1","documentID":51,"attr1":true}"""
  }

  def e9 = {
    val attr1_kv = new AttributeKV(new QName("x"), "99.9")
    val attr2_kv = new AttributeKV(new QName("y"), "11.4")
    val attributes = Seq(Left(IndexableAttribute("attr1", Seq(IndexableValue("1.1", Right(attr1_kv))), LiteralTypeConfig.Float)), Left(IndexableAttribute("attr2", Seq(IndexableValue("1.2", Right(attr2_kv))), LiteralTypeConfig.Float)))
    val indexableRootObject = IndexableRootObject("/db/a1", 3, 52, None, Some("1"), None, attributes)
    serializeJson(indexableRootObject) mustEqual """{"objectID":"3/52/1","collection":"/db/a1","documentID":52,"attr1":99.9,"attr2":11.4}"""
  }

  def e10 = {
    val attr1_1_kv = new AttributeKV(new QName("x"), "99.9")
    val attr1_2_kv = new AttributeKV(new QName("x"), "202.2")
    val attr2_1_kv = new AttributeKV(new QName("y"), "11.4")
    val attr2_2_kv = new AttributeKV(new QName("y"), "10.2")
    val attributes = Seq(
      Left(IndexableAttribute("xx", Seq(IndexableValue("1.1", Right(attr1_1_kv)), IndexableValue("2.1", Right(attr1_2_kv))), LiteralTypeConfig.Float)),
      Left(IndexableAttribute("yy", Seq(IndexableValue("1.2", Right(attr2_1_kv)), IndexableValue("2.2", Right(attr2_2_kv))), LiteralTypeConfig.Float))
    )
    val indexableRootObject = IndexableRootObject("/db/a1", 7, 42, None, Some("1"), None, attributes)
    serializeJson(indexableRootObject) mustEqual """{"objectID":"7/42/1","collection":"/db/a1","documentID":42,"xx":[99.9,202.2],"yy":[11.4,10.2]}"""
  }

  def e11 = {
    val elem1_kv = new ElementKV(new QName("w"), "hello")
    val attributes = Seq(Left(IndexableAttribute("elem1", Seq(IndexableValue("1.1", Left(elem1_kv))), LiteralTypeConfig.String)))
    val indexableRootObject = IndexableRootObject("/db/a1", 6, 48, None, Some("1"), None, attributes)
    serializeJson(indexableRootObject) mustEqual """{"objectID":"6/48/1","collection":"/db/a1","documentID":48,"elem1":"hello"}"""
  }

  def e12 = {
    val elem1_kv = new ElementKV(new QName("x"), "99.9")
    val attributes = Seq(Left(IndexableAttribute("elem1", Seq(IndexableValue("1.1", Left(elem1_kv))), LiteralTypeConfig.Float)))
    val indexableRootObject = IndexableRootObject("/db/a1", 7, 48, None, Some("1"), None, attributes)
    serializeJson(indexableRootObject) mustEqual """{"objectID":"7/48/1","collection":"/db/a1","documentID":48,"elem1":99.9}"""
  }

  def e13 = {
    val elem1_kv = new ElementKV(new QName("y"), "1012")
    val attributes = Seq(Left(IndexableAttribute("elem1", Seq(IndexableValue("1.1", Left(elem1_kv))), LiteralTypeConfig.Integer)))
    val indexableRootObject = IndexableRootObject("/db/a1", 2, 48, None, Some("1"), None, attributes)
    serializeJson(indexableRootObject) mustEqual """{"objectID":"2/48/1","collection":"/db/a1","documentID":48,"elem1":1012}"""
  }

  def e14 = {
    val elem1_kv = new ElementKV(new QName("z"), "true")
    val attributes = Seq(Left(IndexableAttribute("elem1", Seq(IndexableValue("1.1", Left(elem1_kv))), LiteralTypeConfig.Boolean)))
    val indexableRootObject = IndexableRootObject("/db/a1", 1, 48, None, Some("1"), None, attributes)
    serializeJson(indexableRootObject) mustEqual """{"objectID":"1/48/1","collection":"/db/a1","documentID":48,"elem1":true}"""
  }

  def e15 = {
    val elem1_kv = new ElementKV(new QName("x"), "99.9")
    val elem2_kv = new ElementKV(new QName("y"), "11.3")
    val attributes = Seq(Left(IndexableAttribute("elem1", Seq(IndexableValue("1.1", Left(elem1_kv))), LiteralTypeConfig.Float)), Left(IndexableAttribute("elem2", Seq(IndexableValue("1.2", Left(elem2_kv))), LiteralTypeConfig.Float)))
    val indexableRootObject = IndexableRootObject("/db/a1", 7, 48, None, Some("1"), None, attributes)
    serializeJson(indexableRootObject) mustEqual """{"objectID":"7/48/1","collection":"/db/a1","documentID":48,"elem1":99.9,"elem2":11.3}"""
  }

  def e16 = {
    val elem1_kv = new ElementKV(new QName("x"), "hello world")
    val attributes = Seq(Left(IndexableAttribute("elem1", Seq(IndexableValue("1.1", Left(elem1_kv))), LiteralTypeConfig.String)))
    val indexableRootObject = IndexableRootObject("/db/a1", 23, 48, None, Some("1"), None, attributes)
    serializeJson(indexableRootObject) mustEqual """{"objectID":"23/48/1","collection":"/db/a1","documentID":48,"elem1":"hello world"}"""
  }

  def e17 = {
    val elem1 = elem(dom("""<x y="17">hello <b>world</b></x>"""), "x")
    val elem1_kv = new ElementKV(new QName("x"), serializeElementForAttribute(elem1).valueOr(ts => throw ts.head))
    val attributes = Seq(Left(IndexableAttribute("elem1", Seq(IndexableValue("1.1", Left(elem1_kv))), LiteralTypeConfig.String)))
    val indexableRootObject = IndexableRootObject("/db/a1", 23, 48, None, Some("1"), None, attributes)
    serializeJson(indexableRootObject) mustEqual """{"objectID":"23/48/1","collection":"/db/a1","documentID":48,"elem1":"hello world"}"""
  }

  def e18 = {
    val dom1 = dom("""<loc><pos><x>123.4</x><y>-17.45</y></pos><pos><x>456.12</x><y>15.67</y></pos></loc>""")
    val pos = elems(dom1, "pos")
    val elem1_1_kv = new ElementKV(new QName("x"), serializeElementForAttribute(childElem(pos(0), "x")).valueOr(ts => throw ts.head))
    val elem1_2_kv = new ElementKV(new QName("x"), serializeElementForAttribute(childElem(pos(1), "x")).valueOr(ts => throw ts.head))
    val elem2_1_kv = new ElementKV(new QName("y"), serializeElementForAttribute(childElem(pos(0), "y")).valueOr(ts => throw ts.head))
    val elem2_2_kv = new ElementKV(new QName("y"), serializeElementForAttribute(childElem(pos(1), "y")).valueOr(ts => throw ts.head))
    val attributes = Seq(
      Left(IndexableAttribute("xx", Seq(IndexableValue("1.1", Left(elem1_1_kv)), IndexableValue("2.1", Left(elem1_2_kv))), LiteralTypeConfig.Float)),
      Left(IndexableAttribute("yy", Seq(IndexableValue("1.2", Left(elem2_1_kv)), IndexableValue("2.2", Left(elem2_2_kv))), LiteralTypeConfig.Float))
    )
    val indexableRootObject = IndexableRootObject("/db/a1", 7, 42, None, Some("1"), None, attributes)
    serializeJson(indexableRootObject) mustEqual """{"objectID":"7/42/1","collection":"/db/a1","documentID":42,"xx":[123.4,456.12],"yy":[-17.45,15.67]}"""
  }

  def e19 = {
    val dom1 = dom("""<loc><pos><x a="1">123.4</x><y b="2">-17.45</y></pos><pos><x a="8">456.12</x><y b="9">15.67</y></pos></loc>""")
    val pos = elems(dom1, "pos")
    val elem1_1_kv = new ElementKV(new QName("x"), serializeElementForAttribute(childElem(pos(0), "x")).valueOr(ts => throw ts.head))
    val elem1_2_kv = new ElementKV(new QName("x"), serializeElementForAttribute(childElem(pos(1), "x")).valueOr(ts => throw ts.head))
    val elem2_1_kv = new ElementKV(new QName("y"), serializeElementForAttribute(childElem(pos(0), "y")).valueOr(ts => throw ts.head))
    val elem2_2_kv = new ElementKV(new QName("y"), serializeElementForAttribute(childElem(pos(1), "y")).valueOr(ts => throw ts.head))
    val attributes = Seq(
      Left(IndexableAttribute("xx", Seq(IndexableValue("1.1", Left(elem1_1_kv)), IndexableValue("2.1", Left(elem1_2_kv))), LiteralTypeConfig.Float)),
      Left(IndexableAttribute("yy", Seq(IndexableValue("1.2", Left(elem2_1_kv)), IndexableValue("2.2", Left(elem2_2_kv))), LiteralTypeConfig.Float))
    )
    val indexableRootObject = IndexableRootObject("/db/a1", 7, 42, None, Some("1"), None, attributes)
    serializeJson(indexableRootObject) mustEqual """{"objectID":"7/42/1","collection":"/db/a1","documentID":42,"xx":[123.4,456.12],"yy":[-17.45,15.67]}"""
  }

  def e20 = {
    val dom1 = dom("""<parts><w><x>hello</x></w></parts>""")
    val elem1 = firstElem(dom1, "x").get
    val elem1_kv = new ElementKV(new QName("x"), serializeElementForAttribute(elem1).valueOr(ts => throw ts.head))
    val attributes = Seq(
      Left(IndexableAttribute("obj1", Seq(IndexableValue("1.1", Left(elem1_kv))), LiteralTypeConfig.String))
    )
    val indexableRootObject = IndexableRootObject("/db/a1", 6, 53, None, Some("1"), None, attributes)
    serializeJson(indexableRootObject) mustEqual
      """{"objectID":"6/53/1","collection":"/db/a1","documentID":53,"obj1":"hello"}""".stripMargin
  }

  def e21 = {
    val attr1_kv = new AttributeKV(new QName("value"), "hello")
    val objects = Seq(Right(IndexableObject("obj1", Seq(IndexableValue("1.1", Right(attr1_kv))))))
    val indexableRootObject = IndexableRootObject("/db/a1", 45, 48, None, Some("1"), None, objects)
    serializeJson(indexableRootObject) mustEqual """{"objectID":"45/48/1","collection":"/db/a1","documentID":48,"obj1":"hello"}"""
  }

  def e22 = {
    val attr1_1_kv = new AttributeKV(new QName("value"), "hello")
    val attr1_2_kv = new AttributeKV(new QName("value"), "world")
    val objects = Seq(Right(IndexableObject("obj1", Seq(
      IndexableValue("1.1.1", Right(attr1_1_kv)),
      IndexableValue("1.2.1", Right(attr1_2_kv))
    ))))
    val indexableRootObject = IndexableRootObject("/db/a1", 46, 49, None, Some("1"), None, objects)
    serializeJson(indexableRootObject) mustEqual """{"objectID":"46/49/1","collection":"/db/a1","documentID":49,"obj1":["hello","world"]}"""
  }

  def e23 = {
    val elem1 = elem(dom("""<w><x>hello</x><y>world</y></w>"""), "w")
    val elem1_kv = new ElementKV(new QName("w"), serializeElementForObject("obj1", Map.empty, Map.empty)(elem1).valueOr(ts => throw ts.head))
    val objects = Seq(Right(IndexableObject("obj1", Seq(IndexableValue("1.1", Left(elem1_kv))))))
    val indexableRootObject = IndexableRootObject("/db/a1", 5, 48, None, Some("1"), None, objects)
    serializeJson(indexableRootObject) mustEqual """{"objectID":"5/48/1","collection":"/db/a1","documentID":48,"obj1":{"nodeId":"1.1","x":"hello","y":"world"}}"""
  }

  def e24 = {
    val elem1 = elem(dom("""<w><x>hello</x><y><z>world</z><zz>again</zz></y></w>"""), "w")
    val elem1_kv = new ElementKV(new QName("w"), serializeElementForObject("obj1", Map.empty, Map.empty)(elem1).valueOr(ts => throw ts.head))
    val objects = Seq(Right(IndexableObject("obj1", Seq(IndexableValue("1.1", Left(elem1_kv))))))
    val indexableRootObject = IndexableRootObject("/db/a1", 2, 49, None, Some("1"), None, objects)
    serializeJson(indexableRootObject) mustEqual """{"objectID":"2/49/1","collection":"/db/a1","documentID":49,"obj1":{"nodeId":"1.1","x":"hello","y":{"z":"world","zz":"again"}}}"""
  }

  def e25 = {
    val elem1 = elem(dom("""<w><x>hello</x><y>world</y><y>again</y></w>"""), "w")
    val elem1_kv = new ElementKV(new QName("w"), serializeElementForObject("obj1", Map.empty, Map.empty)(elem1).valueOr(ts => throw ts.head))
    val objects = Seq(Right(IndexableObject("obj1", Seq(IndexableValue("1.1", Left(elem1_kv))))))
    val indexableRootObject = IndexableRootObject("/db/a1", 3, 50, None, Some("1"), None, objects)
    serializeJson(indexableRootObject) mustEqual """{"objectID":"3/50/1","collection":"/db/a1","documentID":50,"obj1":{"nodeId":"1.1","x":"hello","y":["world","again"]}}"""
  }

  def e26 = {
    val elem1 = elem(dom("""<w><x>hello</x><y><yy>world</yy><yy>again</yy></y></w>"""), "w")
    val elem1_kv = new ElementKV(new QName("w"), serializeElementForObject("obj1", Map.empty, Map.empty)(elem1).valueOr(ts => throw ts.head))
    val objects = Seq(Right(IndexableObject("obj1", Seq(IndexableValue("1.1", Left(elem1_kv))))))
    val indexableRootObject = IndexableRootObject("/db/a1", 6, 51, None, Some("1"), None, objects)
    serializeJson(indexableRootObject) mustEqual """{"objectID":"6/51/1","collection":"/db/a1","documentID":51,"obj1":{"nodeId":"1.1","x":"hello","y":{"yy":["world","again"]}}}"""
  }

  def e27 = {
    val dom1 = dom("""<parts><w><x>hello</x><y><yy>world</yy><yy>again</yy></y></w><w><x>goodbye</x><y><yy>until</yy><yy>next time</yy></y></w></parts>""")
    val ww = elems(dom1, "w")
    val elem1_kv = new ElementKV(new QName("w"), serializeElementForObject("obj1", Map.empty, Map.empty)(ww(0)).valueOr(ts => throw ts.head))
    val elem2_kv = new ElementKV(new QName("w"), serializeElementForObject("obj1", Map.empty, Map.empty)(ww(1)).valueOr(ts => throw ts.head))
    val objects = Seq(Right(IndexableObject("obj1", Seq(
      IndexableValue("1.1", Left(elem1_kv)),
      IndexableValue("1.2", Left(elem2_kv))
    ))))
    val indexableRootObject = IndexableRootObject("/db/a1", 6, 52, None, Some("1"), None, objects)
    serializeJson(indexableRootObject) mustEqual """{"objectID":"6/52/1","collection":"/db/a1","documentID":52,"obj1":[{"nodeId":"1.1","x":"hello","y":{"yy":["world","again"]}},{"nodeId":"1.2","x":"goodbye","y":{"yy":["until","next time"]}}]}"""
  }

  def e28 = {
    val dom1 = dom("""<parts><w><x>hello</x></w></parts>""")
    val elem1 = firstElem(dom1, "x").get
    val elem1_kv = new ElementKV(new QName("x"), serializeElementForObject("obj1", Map.empty, Map.empty)(elem1).valueOr(ts => throw ts.head))
    val objects = Seq(Right(IndexableObject("obj1", Seq(
      IndexableValue("1.1", Left(elem1_kv))
    ))))
    val indexableRootObject = IndexableRootObject("/db/a1", 6, 53, None, Some("1"), None, objects)
    serializeJson(indexableRootObject) mustEqual
      """{"objectID":"6/53/1","collection":"/db/a1","documentID":53,"obj1":{"nodeId":"1.1","#text":"hello"}}""".stripMargin
  }

  def e29 = {
    val dom1 = dom("""<parts><w><x type="something"/></w></parts>""")
    val elem1 = firstElem(dom1, "x").get
    val elem1_kv = new ElementKV(new QName("x"), serializeElementForObject("obj1", Map.empty, Map.empty)(elem1).valueOr(ts => throw ts.head))
    val objects = Seq(Right(IndexableObject("obj1", Seq(
      IndexableValue("1.1", Left(elem1_kv))
    ))))
    val indexableRootObject = IndexableRootObject("/db/a1", 6, 53, None, Some("1"), None, objects)
    serializeJson(indexableRootObject) mustEqual
      """{"objectID":"6/53/1","collection":"/db/a1","documentID":53,"obj1":{"nodeId":"1.1","type":"something"}}""".stripMargin
  }

  def e30 = {
    val dom1 = dom("""<parts><w><x type="something">hello</x></w></parts>""")
    val elem = firstElem(dom1, "x").get
    val elem1_kv = new ElementKV(new QName("x"), serializeElementForObject("obj1", Map.empty, Map.empty)(elem).valueOr(ts => throw ts.head))
    val objects = Seq(Right(IndexableObject("obj1", Seq(
      IndexableValue("1.1", Left(elem1_kv))
    ))))
    val indexableRootObject = IndexableRootObject("/db/a1", 6, 53, None, Some("1"), None, objects)
    serializeJson(indexableRootObject) mustEqual
      """{"objectID":"6/53/1","collection":"/db/a1","documentID":53,"obj1":{"nodeId":"1.1","type":"something","#text":"hello"}}""".stripMargin
  }

  private def serializeJson(indexableRootObject: IndexableRootObject): String = {
    Using(new StringWriter()) { writer =>
        val mapper = new ObjectMapper
        mapper.writeValue(writer, indexableRootObject)
        writer.toString
    }.get
  }
}
