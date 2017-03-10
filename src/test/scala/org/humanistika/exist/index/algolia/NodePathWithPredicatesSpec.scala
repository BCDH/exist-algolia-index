package org.humanistika.exist.index.algolia

import javax.xml.namespace.QName

import org.humanistika.exist.index.algolia.NodePathWithPredicates._
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

      from relative attribute with predicate eq string $e18
      from relative element with predicate ne string $e19
      from single component attribute with predicate eq string $e20
      from single component element with predicate ne string $e21
      from /x[@a eq '1']/y[@b ne '2'][@c = '3']/z/@other string $e22
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
    npwp.get(0) must beEqualTo(Component(TypedQName(new QName("something"), ComponentType.ATTRIBUTE)))
  }

  def e2 = {
    val npwp = NodePathWithPredicates(namespaces, "something")
    npwp.size must beEqualTo(1)
    npwp.get(0) must beEqualTo(Component(TypedQName(new QName("something"), ComponentType.ELEMENT)))
  }

  def e3 = {
    val npwp = NodePathWithPredicates(namespaces, "@unk:blah")
    npwp.size must beEqualTo(1)
    npwp.get(0) must beEqualTo(Component(TypedQName(new QName(UNK_NS, "blah", UNK_PREFIX), ComponentType.ATTRIBUTE)))
  }

  def e4 = {
    val npwp = NodePathWithPredicates(namespaces, "tei:form")
    npwp.size must beEqualTo(1)
    npwp.get(0) must beEqualTo(Component(TypedQName(new QName(TEI_NS, "form", TEI_PREFIX), ComponentType.ELEMENT)))
  }

  def e5 = {
    val npwp = NodePathWithPredicates(namespaces, "/@something")
    npwp.size must beEqualTo(1)
    npwp.get(0) must beEqualTo(Component(TypedQName(new QName("something"), ComponentType.ATTRIBUTE)))
  }

  def e6 = {
    val npwp = NodePathWithPredicates(namespaces, "/something")
    npwp.size must beEqualTo(1)
    npwp.get(0) must beEqualTo(Component(TypedQName(new QName("something"), ComponentType.ELEMENT)))
  }

  def e7 = {
    val npwp = NodePathWithPredicates(namespaces, "/@unk:blah")
    npwp.size must beEqualTo(1)
    npwp.get(0) must beEqualTo(Component(TypedQName(new QName(UNK_NS, "blah", UNK_PREFIX), ComponentType.ATTRIBUTE)))
  }

  def e8 = {
    val npwp = NodePathWithPredicates(namespaces, "/tei:form")
    npwp.size must beEqualTo(1)
    npwp.get(0) must beEqualTo(Component(TypedQName(new QName(TEI_NS, "form", TEI_PREFIX), ComponentType.ELEMENT)))
  }

  def e9 = {
    val npwp = NodePathWithPredicates(namespaces, "*")
    npwp.size must beEqualTo(1)
    npwp.get(0) must beEqualTo(Component(TypedQName(new QName("*"), ComponentType.ANY)))
  }

  def e10 = {
    val npwp = NodePathWithPredicates(namespaces, "/*")
    npwp.size must beEqualTo(1)
    npwp.get(0) must beEqualTo(Component(TypedQName(new QName("*"), ComponentType.ANY)))
  }

  def e11 = {
    val npwp = NodePathWithPredicates(namespaces, "/x/y/z/@other")
    npwp.size must beEqualTo(4)
    npwp.get(0) must beEqualTo(Component(TypedQName(new QName("x"), ComponentType.ELEMENT)))
    npwp.get(1) must beEqualTo(Component(TypedQName(new QName("y"), ComponentType.ELEMENT)))
    npwp.get(2) must beEqualTo(Component(TypedQName(new QName("z"), ComponentType.ELEMENT)))
    npwp.get(3) must beEqualTo(Component(TypedQName(new QName("other"), ComponentType.ATTRIBUTE)))
  }

  def e12 = {
    val npwp = NodePathWithPredicates(namespaces, "/unk:x/unk:y/unk:z/@tei:form")
    npwp.size must beEqualTo(4)
    npwp.get(0) must beEqualTo(Component(TypedQName(new QName(UNK_NS, "x", UNK_PREFIX), ComponentType.ELEMENT)))
    npwp.get(1) must beEqualTo(Component(TypedQName(new QName(UNK_NS, "y", UNK_PREFIX), ComponentType.ELEMENT)))
    npwp.get(2) must beEqualTo(Component(TypedQName(new QName(UNK_NS, "z", UNK_PREFIX), ComponentType.ELEMENT)))
    npwp.get(3) must beEqualTo(Component(TypedQName(new QName(TEI_NS, "form", TEI_PREFIX), ComponentType.ATTRIBUTE)))
  }

  def e13 = {
    val npwp = NodePathWithPredicates(namespaces, "//@deep")
    npwp.size must beEqualTo(2)
    npwp.get(0) must beEqualTo(Component(TypedQName(new QName("//"), ComponentType.ELEMENT)))
    npwp.get(1) must beEqualTo(Component(TypedQName(new QName("deep"), ComponentType.ATTRIBUTE)))
  }

  def e14 = {
    val npwp = NodePathWithPredicates(namespaces, "//deep")
    npwp.size must beEqualTo(2)
    npwp.get(0) must beEqualTo(Component(TypedQName(new QName("//"), ComponentType.ELEMENT)))
    npwp.get(1) must beEqualTo(Component(TypedQName(new QName("deep"), ComponentType.ELEMENT)))
  }

  def e15 = {
    val npwp = NodePathWithPredicates(namespaces, "//deep1//deep2")
    npwp.size must beEqualTo(4)
    npwp.get(0) must beEqualTo(Component(TypedQName(new QName("//"), ComponentType.ELEMENT)))
    npwp.get(1) must beEqualTo(Component(TypedQName(new QName("deep1"), ComponentType.ELEMENT)))
    npwp.get(2) must beEqualTo(Component(TypedQName(new QName("//"), ComponentType.ELEMENT)))
    npwp.get(3) must beEqualTo(Component(TypedQName(new QName("deep2"), ComponentType.ELEMENT)))
  }

  def e16 = {
    val npwp = NodePathWithPredicates(namespaces, "//deep1/shallow//deep2")
    npwp.size must beEqualTo(5)
    npwp.get(0) must beEqualTo(Component(TypedQName(new QName("//"), ComponentType.ELEMENT)))
    npwp.get(1) must beEqualTo(Component(TypedQName(new QName("deep1"), ComponentType.ELEMENT)))
    npwp.get(2) must beEqualTo(Component(TypedQName(new QName("shallow"), ComponentType.ELEMENT)))
    npwp.get(3) must beEqualTo(Component(TypedQName(new QName("//"), ComponentType.ELEMENT)))
    npwp.get(4) must beEqualTo(Component(TypedQName(new QName("deep2"), ComponentType.ELEMENT)))
  }

  def e17 = {
    val npwp = NodePathWithPredicates(namespaces, "/shallow//deep")
    npwp.size must beEqualTo(3)
    npwp.get(0) must beEqualTo(Component(TypedQName(new QName("shallow"), ComponentType.ELEMENT)))
    npwp.get(1) must beEqualTo(Component(TypedQName(new QName("//"), ComponentType.ELEMENT)))
    npwp.get(2) must beEqualTo(Component(TypedQName(new QName("deep"), ComponentType.ELEMENT)))
  }

  def e18 = {
    NodePathWithPredicates(namespaces, "@something[@x eq 'y']") must throwA[IllegalArgumentException]
  }

  def e19 = {
    val npwp = NodePathWithPredicates(namespaces, "something[@x ne 'y']")
    npwp.size must beEqualTo(1)
    npwp.get(0) must beEqualTo(Component(TypedQName(new QName("something"), ComponentType.ELEMENT), Seq(
      Predicate(TypedQName(new QName("x"), ComponentType.ATTRIBUTE), AtomicNotEqualsComparison, Seq("y"))
    )))
  }

  def e20 = {
    NodePathWithPredicates(namespaces, "/@something[@a eq 'b']") must throwA[IllegalArgumentException]
  }

  def e21 = {
    val npwp = NodePathWithPredicates(namespaces, "/something[@a ne 'b']")
    npwp.size must beEqualTo(1)
    npwp.get(0) must beEqualTo(Component(TypedQName(new QName("something"), ComponentType.ELEMENT), Seq(
      Predicate(TypedQName(new QName("a"), ComponentType.ATTRIBUTE), AtomicNotEqualsComparison, Seq("b"))
    )))
  }

  def e22 = {
    val npwp = NodePathWithPredicates(namespaces, "/x[@a eq '1']/y[@b ne '2'][@c = '3']/z/@other")
    npwp.size must beEqualTo(4)
    npwp.get(0) must beEqualTo(Component(TypedQName(new QName("x"), ComponentType.ELEMENT), Seq(
      Predicate(TypedQName(new QName("a"), ComponentType.ATTRIBUTE), AtomicEqualsComparison, Seq("1"))
    )))
    npwp.get(1) must beEqualTo(Component(TypedQName(new QName("y"), ComponentType.ELEMENT), Seq(
      Predicate(TypedQName(new QName("b"), ComponentType.ATTRIBUTE), AtomicEqualsComparison, Seq("2")),
      Predicate(TypedQName(new QName("c"), ComponentType.ATTRIBUTE), AtomicEqualsComparison, Seq("3"))
    )))
    npwp.get(2) must beEqualTo(Component(TypedQName(new QName("z"), ComponentType.ELEMENT)))
    npwp.get(3) must beEqualTo(Component(TypedQName(new QName("other"), ComponentType.ATTRIBUTE)))
  }
}
