package org.humanistika.exist.index.algolia

import javax.xml.namespace.QName

import org.specs2.Specification

/**
  * Created by aretter on 09/03/2017.
  */
class NodePathWithPredicatesSpec extends Specification { def is = s2""" {
  This is a specification to check the NodePathWithPredicates

    constructor
     from relative attribute string $e1
      from relative element string $e2
      from relative attribute string with ns $e3
      from relative element string with ns $e4
      from single component attribute string $e5
      from single component element string $e6
      from single component attribute string with ns $e7
      from single component element string with ns $e8
      from wildcard string $e9
      from single wildcard component string $e10
      from /x/y/z/@other string $e11
      from /unk:x/unk:y/unk:z/@tei:form string $e12
      from //@deep string $e13
      from //deep string $e14
      from //deep1//deep2 string $e15
      from //deep1/shallow//deep2 string $e16
      from /shallow//deep string $e17
    """

  val UNK_PREFIX = "unk"
  val UNK_NS = "http://unknown"

  val TEI_PREFIX = "tei"
  val TEI_NS = "http://www.tei-c.org/ns/1.0"


  val namespaces = Map(
    UNK_PREFIX -> UNK_NS,
    TEI_PREFIX -> TEI_NS
  )

  def e1 = {
    val npwp = NodePathWithPredicates(namespaces, "@something")
    npwp.size must beEqualTo(1)
    npwp.get(0) must beEqualTo(TypedQName(new QName("something"), ComponentType.ATTRIBUTE))
  }

  def e2 = {
    val npwp = NodePathWithPredicates(namespaces, "something")
    npwp.size must beEqualTo(1)
    npwp.get(0) must beEqualTo(TypedQName(new QName("something"), ComponentType.ELEMENT))
  }

  def e3 = {
    val npwp = NodePathWithPredicates(namespaces, "@unk:blah")
    npwp.size must beEqualTo(1)
    npwp.get(0) must beEqualTo(TypedQName(new QName(UNK_NS, "blah", UNK_PREFIX), ComponentType.ATTRIBUTE))
  }

  def e4 = {
    val npwp = NodePathWithPredicates(namespaces, "tei:form")
    npwp.size must beEqualTo(1)
    npwp.get(0) must beEqualTo(TypedQName(new QName(TEI_NS, "form", TEI_PREFIX), ComponentType.ELEMENT))
  }

  def e5 = {
    val npwp = NodePathWithPredicates(namespaces, "/@something")
    npwp.size must beEqualTo(1)
    npwp.get(0) must beEqualTo(TypedQName(new QName("something"), ComponentType.ATTRIBUTE))
  }

  def e6 = {
    val npwp = NodePathWithPredicates(namespaces, "/something")
    npwp.size must beEqualTo(1)
    npwp.get(0) must beEqualTo(TypedQName(new QName("something"), ComponentType.ELEMENT))
  }

  def e7 = {
    val npwp = NodePathWithPredicates(namespaces, "/@unk:blah")
    npwp.size must beEqualTo(1)
    npwp.get(0) must beEqualTo(TypedQName(new QName(UNK_NS, "blah", UNK_PREFIX), ComponentType.ATTRIBUTE))
  }

  def e8 = {
    val npwp = NodePathWithPredicates(namespaces, "/tei:form")
    npwp.size must beEqualTo(1)
    npwp.get(0) must beEqualTo(TypedQName(new QName(TEI_NS, "form", TEI_PREFIX), ComponentType.ELEMENT))
  }

  def e9 = {
    val npwp = NodePathWithPredicates(namespaces, "*")
    npwp.size must beEqualTo(1)
    npwp.get(0) must beEqualTo(TypedQName(new QName("*"), ComponentType.ANY))
  }

  def e10 = {
    val npwp = NodePathWithPredicates(namespaces, "/*")
    npwp.size must beEqualTo(1)
    npwp.get(0) must beEqualTo(TypedQName(new QName("*"), ComponentType.ANY))
  }

  def e11 = {
    val npwp = NodePathWithPredicates(namespaces, "/x/y/z/@other")
    npwp.size must beEqualTo(4)
    npwp.get(0) must beEqualTo(TypedQName(new QName("x"), ComponentType.ELEMENT))
    npwp.get(1) must beEqualTo(TypedQName(new QName("y"), ComponentType.ELEMENT))
    npwp.get(2) must beEqualTo(TypedQName(new QName("z"), ComponentType.ELEMENT))
    npwp.get(3) must beEqualTo(TypedQName(new QName("other"), ComponentType.ATTRIBUTE))
  }

  def e12 = {
    val npwp = NodePathWithPredicates(namespaces, "/unk:x/unk:y/unk:z/@tei:form")
    npwp.size must beEqualTo(4)
    npwp.get(0) must beEqualTo(TypedQName(new QName(UNK_NS, "x", UNK_PREFIX), ComponentType.ELEMENT))
    npwp.get(1) must beEqualTo(TypedQName(new QName(UNK_NS, "y", UNK_PREFIX), ComponentType.ELEMENT))
    npwp.get(2) must beEqualTo(TypedQName(new QName(UNK_NS, "z", UNK_PREFIX), ComponentType.ELEMENT))
    npwp.get(3) must beEqualTo(TypedQName(new QName(TEI_NS, "form", TEI_PREFIX), ComponentType.ATTRIBUTE))
  }

  def e13 = {
    val npwp = NodePathWithPredicates(namespaces, "//@deep")
    npwp.size must beEqualTo(2)
    npwp.get(0) must beEqualTo(TypedQName(new QName("//"), ComponentType.ELEMENT))
    npwp.get(1) must beEqualTo(TypedQName(new QName("deep"), ComponentType.ATTRIBUTE))
  }

  def e14 = {
    val npwp = NodePathWithPredicates(namespaces, "//deep")
    npwp.size must beEqualTo(2)
    npwp.get(0) must beEqualTo(TypedQName(new QName("//"), ComponentType.ELEMENT))
    npwp.get(1) must beEqualTo(TypedQName(new QName("deep"), ComponentType.ELEMENT))
  }

  def e15 = {
    val npwp = NodePathWithPredicates(namespaces, "//deep1//deep2")
    npwp.size must beEqualTo(4)
    npwp.get(0) must beEqualTo(TypedQName(new QName("//"), ComponentType.ELEMENT))
    npwp.get(1) must beEqualTo(TypedQName(new QName("deep1"), ComponentType.ELEMENT))
    npwp.get(2) must beEqualTo(TypedQName(new QName("//"), ComponentType.ELEMENT))
    npwp.get(3) must beEqualTo(TypedQName(new QName("deep2"), ComponentType.ELEMENT))
  }

  def e16 = {
    val npwp = NodePathWithPredicates(namespaces, "//deep1/shallow//deep2")
    npwp.size must beEqualTo(5)
    npwp.get(0) must beEqualTo(TypedQName(new QName("//"), ComponentType.ELEMENT))
    npwp.get(1) must beEqualTo(TypedQName(new QName("deep1"), ComponentType.ELEMENT))
    npwp.get(2) must beEqualTo(TypedQName(new QName("shallow"), ComponentType.ELEMENT))
    npwp.get(3) must beEqualTo(TypedQName(new QName("//"), ComponentType.ELEMENT))
    npwp.get(4) must beEqualTo(TypedQName(new QName("deep2"), ComponentType.ELEMENT))
  }

  def e17 = {
    val npwp = NodePathWithPredicates(namespaces, "/shallow//deep")
    npwp.size must beEqualTo(3)
    npwp.get(0) must beEqualTo(TypedQName(new QName("shallow"), ComponentType.ELEMENT))
    npwp.get(1) must beEqualTo(TypedQName(new QName("//"), ComponentType.ELEMENT))
    npwp.get(2) must beEqualTo(TypedQName(new QName("deep"), ComponentType.ELEMENT))
  }
}
