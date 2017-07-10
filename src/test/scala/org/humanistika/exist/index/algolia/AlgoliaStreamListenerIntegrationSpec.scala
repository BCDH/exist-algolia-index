package org.humanistika.exist.index.algolia

import java.nio.file.{Path, Paths}

import scalaz._
import Scalaz._

import akka.actor.{ActorRef, ActorSystem}
import org.exist.collections.CollectionConfiguration
import org.exist.indexing.IndexWorker
import org.exist.storage.{BrokerPool, DBBroker, ScalaBrokerPoolBridge}
import org.exist.util.FileInputSource
import org.exist.xmldb.XmldbURI
import org.humanistika.exist.index.algolia.AlgoliaIndex.Authentication
import org.humanistika.exist.index.algolia.backend.IncrementalIndexingManagerActor.{Add, FinishDocument, StartDocument}
import org.specs2.mutable.Specification
import org.w3c.dom.Element

import AlgoliaStreamListenerIntegrationSpec._
import ExistAPIHelper._

object AlgoliaStreamListenerIntegrationSpec {
  def getTestResource(filename: String): Path = Paths.get(classOf[AlgoliaStreamListenerIntegrationSpec].getClassLoader.getResource(filename).toURI)
}

/**
  * Created by aretter on 07/05/2017.
  */
class AlgoliaStreamListenerIntegrationSpec extends Specification with ExistServerForEach {
  // Set sequential execution
  sequential

  override val configFile = Option(getTestResource("conf.xml"))


  "AlgoliaStreamListener when indexing a document" should {

    "produce the correct actor messages for a basic index config" in new AkkaTestkitSpecs2Support {

      val indexName = "raskovnik-test-integration-basic"
      val testCollectionPath = XmldbURI.create("/db/test-integration-basic")

      // register our index
      implicit val brokerPool = getBrokerPool
      val algoliaIndex = createAndRegisterAlgoliaIndex(system, Some(testActor))

      // set up an index configuration
      storeCollectionConfig(algoliaIndex, testCollectionPath, getTestResource("integration/basic/collection.xconf"))

      // store some data (which will be indexed)
      val (collectionId, docId) = storeTestDocument(algoliaIndex, testCollectionPath, getTestResource("integration/basic/VSK.TEST.xml"))

      collectionId mustNotEqual -1
      docId mustNotEqual -1

      val collectionPath = testCollectionPath.getRawCollectionPath

      expectMsg(Authentication("some-application-id", "some-admin-api-key"))
      expectMsg(StartDocument(indexName, collectionId, docId))
      assertAdd(expectMsgType[Add])(indexName, collectionPath, collectionId, docId, None, Some("1.5.2.2.4"), None, Seq(
          -\/(("dict",  Seq("1.5.2.2.4.1"))),
          -\/(("lemma", Seq("1.5.2.2.4.3.3"))),
          -\/(("tr",    Seq("1.5.2.2.4.9.3.3")))))
      assertAdd(expectMsgType[Add])(indexName, collectionPath, collectionId, docId, None, Some("1.5.2.2.6"), None, Seq(
          -\/(("dict",  Seq("1.5.2.2.6.1"))),
          -\/(("lemma", Seq("1.5.2.2.6.3.3"))),
          -\/(("tr",    Seq("1.5.2.2.6.9.3.3")))))
      assertAdd(expectMsgType[Add])(indexName, collectionPath, collectionId, docId, None, Some("1.5.2.2.8"), None, Seq(
          -\/(("dict",  Seq("1.5.2.2.8.1"))),
          -\/(("lemma", Seq("1.5.2.2.8.3.3"))),
          -\/(("tr",    Seq("1.5.2.2.8.9.3.3")))))
      assertAdd(expectMsgType[Add])(indexName, collectionPath, collectionId, docId, None, Some("1.5.2.2.10"), None, Seq(
          -\/(("dict",  Seq("1.5.2.2.10.1"))),
          -\/(("lemma", Seq("1.5.2.2.10.3.3"))),
          -\/(("tr",    Seq("1.5.2.2.10.9.3.3")))))
      assertAdd(expectMsgType[Add])(indexName, collectionPath, collectionId, docId, None, Some("1.5.2.2.12"), None, Seq(
          -\/(("dict",  Seq("1.5.2.2.12.1"))),
          -\/(("lemma", Seq("1.5.2.2.12.3.3"))),
          -\/(("tr",    Seq("1.5.2.2.12.9.3.3")))))
      expectMsg(FinishDocument(indexName, None, collectionId, docId))
    }


    "produce the correct actor messages for elements with attributes" in new AkkaTestkitSpecs2Support {

      val indexName = "raskovnik-test-integration-element-without-attributes"
      val testCollectionPath = XmldbURI.create("/db/test-integration-element-without-attributes")

      // register our index
      implicit val brokerPool = getBrokerPool
      val algoliaIndex = createAndRegisterAlgoliaIndex(system, Some(testActor))

      // set up an index configuration
      storeCollectionConfig(algoliaIndex, testCollectionPath, getTestResource("integration/element-without-attributes/collection.xconf"))

      // store some data (which will be indexed)
      val (collectionId, docId) = storeTestDocument(algoliaIndex, testCollectionPath, getTestResource("integration/element-without-attributes/algolia-test.xml"))

      collectionId mustNotEqual -1
      docId mustNotEqual -1

      val collectionPath = testCollectionPath.getRawCollectionPath

      expectMsg(Authentication("some-application-id", "some-admin-api-key"))
      expectMsg(StartDocument(indexName, collectionId, docId))
      assertAdd(expectMsgType[Add])(indexName, collectionPath, collectionId, docId, None, Some("1.5.2.2.4"), None, Seq(
          -\/(("lemma", Seq(
              "1.5.2.2.4.6.3",
              "1.5.2.2.4.8.5",
              "1.5.2.2.4.12.5")))))
      assertAdd(expectMsgType[Add])(indexName, collectionPath, collectionId, docId, None, Some("1.5.2.2.6"), None, Seq(
          -\/(("lemma", Seq(
              "1.5.2.2.6.6.3")))))
      expectMsg(FinishDocument(indexName, None, collectionId, docId))
    }


    "produce the correct actor messages for a index config with predicates" in new AkkaTestkitSpecs2Support {

      val indexName = "raskovnik-test-integration-predicate"
      val testCollectionPath = XmldbURI.create("/db/test-integration-predicate")

      // register our index
      implicit val brokerPool = getBrokerPool
      val algoliaIndex = createAndRegisterAlgoliaIndex(system, Some(testActor))

      // set up an index configuration
      storeCollectionConfig(algoliaIndex, testCollectionPath, getTestResource("integration/predicate/collection.xconf"))

      // store some data (which will be indexed)
      val (collectionId, docId) = storeTestDocument(algoliaIndex, testCollectionPath, getTestResource("integration/predicate/VSK.TEST.xml"))

      collectionId mustNotEqual -1
      docId mustNotEqual -1

      val collectionPath = testCollectionPath.getRawCollectionPath

      expectMsg(Authentication("some-application-id", "some-admin-api-key"))
      expectMsg(StartDocument(indexName, collectionId, docId))
      assertAdd(expectMsgType[Add])(indexName, collectionPath, collectionId, docId, None, Some("1.5.2.2.4"), None, Seq(
        -\/(("dict",  Seq("1.5.2.2.4.1"))),
        -\/(("lemma", Seq("1.5.2.2.4.3.3"))),
        -\/(("tr",    Seq("1.5.2.2.4.9.3.3")))))
      assertAdd(expectMsgType[Add])(indexName, collectionPath, collectionId, docId, None, Some("1.5.2.2.6"), None, Seq(
        -\/(("dict",  Seq("1.5.2.2.6.1"))),
        -\/(("lemma", Seq("1.5.2.2.6.3.3"))),
        -\/(("tr",    Seq("1.5.2.2.6.9.3.3")))))
      assertAdd(expectMsgType[Add])(indexName, collectionPath, collectionId, docId, None, Some("1.5.2.2.8"), None, Seq(
        -\/(("dict",  Seq("1.5.2.2.8.1"))),
        -\/(("lemma", Seq("1.5.2.2.8.3.3"))),
        -\/(("tr",    Seq("1.5.2.2.8.9.3.3")))))
      assertAdd(expectMsgType[Add])(indexName, collectionPath, collectionId, docId, None, Some("1.5.2.2.10"), None, Seq(
        -\/(("dict",          Seq("1.5.2.2.10.1"))),
        -\/(("inverse-lemma", Seq("1.5.2.2.10.3.3"))),
        -\/(("tr",            Seq("1.5.2.2.10.9.3.3")))))
      assertAdd(expectMsgType[Add])(indexName, collectionPath, collectionId, docId, None, Some("1.5.2.2.12"), None, Seq(
        -\/(("dict",  Seq("1.5.2.2.12.1"))),
        -\/(("lemma", Seq("1.5.2.2.12.3.3")))))
      expectMsg(FinishDocument(indexName, None, collectionId, docId))
    }

    "produce the correct actor messages for a index config with multiple predicates on a path" in new AkkaTestkitSpecs2Support {

      val indexName = "raskovnik-test-integration-multi-predicates"
      val testCollectionPath = XmldbURI.create("/db/test-integration-multi-predicates")

      // register our index
      implicit val brokerPool = getBrokerPool
      val algoliaIndex = createAndRegisterAlgoliaIndex(system, Some(testActor))

      // set up an index configuration
      storeCollectionConfig(algoliaIndex, testCollectionPath, getTestResource("integration/multi-predicates/collection.xconf"))

      // store some data (which will be indexed)
      val (collectionId, docId) = storeTestDocument(algoliaIndex, testCollectionPath, getTestResource("integration/multi-predicates/algolia-test.xml"))

      collectionId mustNotEqual -1
      docId mustNotEqual -1

      val collectionPath = testCollectionPath.getRawCollectionPath

      expectMsg(Authentication("some-application-id", "some-admin-api-key"))
      expectMsg(StartDocument(indexName, collectionId, docId))
      assertAdd(expectMsgType[Add])(indexName, collectionPath, collectionId, docId, None, Some("1.5.2.2.4"), None, Seq(
          -\/(("trde", Seq("1.5.2.2.4.14.6.3", "1.5.2.2.4.16.6.3", "1.5.2.2.4.18.6.3", "1.5.2.2.4.18.8.3", "1.5.2.2.4.22.6.3", "1.5.2.2.4.24.8.3", "1.5.2.2.4.26.6.3"))),
          -\/(("trla", Seq("1.5.2.2.4.14.8.3", "1.5.2.2.4.14.10.5", "1.5.2.2.4.16.8.3", "1.5.2.2.4.18.10.3", "1.5.2.2.4.18.12.3", "1.5.2.2.4.22.8.3", "1.5.2.2.4.24.10.3", "1.5.2.2.4.26.8.3")))))
      expectMsg(FinishDocument(indexName, None, collectionId, docId))
    }


    "produce the correct actor messages for a basic index config with user specified docId" in new AkkaTestkitSpecs2Support {

      val indexName = "raskovnik-test-integration-user-specified-docId"
      val userSpecifiedDocId = "VSK.TEST"
      val testCollectionPath = XmldbURI.create("/db/test-integration-user-specified-docId")

      // register our index
      implicit val brokerPool = getBrokerPool
      val algoliaIndex = createAndRegisterAlgoliaIndex(system, Some(testActor))

      // set up an index configuration
      storeCollectionConfig(algoliaIndex, testCollectionPath, getTestResource("integration/user-specified-docId/collection.xconf"))

      // store some data (which will be indexed)
      val (collectionId, docId) = storeTestDocument(algoliaIndex, testCollectionPath, getTestResource("integration/user-specified-docId/VSK.TEST.xml"))

      collectionId mustNotEqual -1
      docId mustNotEqual -1

      val collectionPath = testCollectionPath.getRawCollectionPath

      expectMsg(Authentication("some-application-id", "some-admin-api-key"))
      expectMsg(StartDocument(indexName, collectionId, docId))
      assertAdd(expectMsgType[Add])(indexName, collectionPath, collectionId, docId, Some(userSpecifiedDocId), Some("1.5.2.2.4"), None, Seq(
        -\/(("dict",  Seq("1.5.2.2.4.1"))),
        -\/(("lemma", Seq("1.5.2.2.4.3.3"))),
        -\/(("tr",    Seq("1.5.2.2.4.9.3.3")))))
      assertAdd(expectMsgType[Add])(indexName, collectionPath, collectionId, docId, Some(userSpecifiedDocId), Some("1.5.2.2.6"), None, Seq(
        -\/(("dict",  Seq("1.5.2.2.6.1"))),
        -\/(("lemma", Seq("1.5.2.2.6.3.3"))),
        -\/(("tr",    Seq("1.5.2.2.6.9.3.3")))))
      assertAdd(expectMsgType[Add])(indexName, collectionPath, collectionId, docId, Some(userSpecifiedDocId), Some("1.5.2.2.8"), None, Seq(
        -\/(("dict",  Seq("1.5.2.2.8.1"))),
        -\/(("lemma", Seq("1.5.2.2.8.3.3"))),
        -\/(("tr",    Seq("1.5.2.2.8.9.3.3")))))
      assertAdd(expectMsgType[Add])(indexName, collectionPath, collectionId, docId, Some(userSpecifiedDocId), Some("1.5.2.2.10"), None, Seq(
        -\/(("dict",  Seq("1.5.2.2.10.1"))),
        -\/(("lemma", Seq("1.5.2.2.10.3.3"))),
        -\/(("tr",    Seq("1.5.2.2.10.9.3.3")))))
      assertAdd(expectMsgType[Add])(indexName, collectionPath, collectionId, docId, Some(userSpecifiedDocId), Some("1.5.2.2.12"), None, Seq(
        -\/(("dict",  Seq("1.5.2.2.12.1"))),
        -\/(("lemma", Seq("1.5.2.2.12.3.3"))),
        -\/(("tr",    Seq("1.5.2.2.12.9.3.3")))))
      expectMsg(FinishDocument(indexName, Some(userSpecifiedDocId), collectionId, docId))
    }


    "produce the correct actor messages for a basic index config with user specified docId and nodeId" in new AkkaTestkitSpecs2Support {

      val indexName = "raskovnik-test-integration-user-specified-docId-and-nodeId"
      val userSpecifiedDocId = "VSK.TEST"
      val testCollectionPath = XmldbURI.create("/db/test-integration-user-specified-docId-and-nodeId")

      // register our index
      implicit val brokerPool = getBrokerPool
      val algoliaIndex = createAndRegisterAlgoliaIndex(system, Some(testActor))

      // set up an index configuration
      storeCollectionConfig(algoliaIndex, testCollectionPath, getTestResource("integration/user-specified-docId-and-nodeId/collection.xconf"))

      // store some data (which will be indexed)
      val (collectionId, docId) = storeTestDocument(algoliaIndex, testCollectionPath, getTestResource("integration/user-specified-docId-and-nodeId/VSK.TEST.xml"))

      collectionId mustNotEqual -1
      docId mustNotEqual -1

      val collectionPath = testCollectionPath.getRawCollectionPath

      expectMsg(Authentication("some-application-id", "some-admin-api-key"))
      expectMsg(StartDocument(indexName, collectionId, docId))
      assertAdd(expectMsgType[Add])(indexName, collectionPath, collectionId, docId, Some(userSpecifiedDocId), Some("1.5.2.2.4"), Some("VSK.SR.Adam"), Seq(
      -\/(("dict",  Seq("1.5.2.2.4.1"))),
      -\/(("lemma", Seq("1.5.2.2.4.3.3"))),
      -\/(("tr",    Seq("1.5.2.2.4.9.3.3")))))
      assertAdd(expectMsgType[Add])(indexName, collectionPath, collectionId, docId, Some(userSpecifiedDocId), Some("1.5.2.2.6"), Some("VSK.SR.Addam"), Seq(
      -\/(("dict",  Seq("1.5.2.2.6.1"))),
      -\/(("lemma", Seq("1.5.2.2.6.3.3"))),
      -\/(("tr",    Seq("1.5.2.2.6.9.3.3")))))
      assertAdd(expectMsgType[Add])(indexName, collectionPath, collectionId, docId, Some(userSpecifiedDocId), Some("1.5.2.2.8"), Some("VSK.SR.Adamm"), Seq(
      -\/(("dict",  Seq("1.5.2.2.8.1"))),
      -\/(("lemma", Seq("1.5.2.2.8.3.3"))),
      -\/(("tr",    Seq("1.5.2.2.8.9.3.3")))))
      assertAdd(expectMsgType[Add])(indexName, collectionPath, collectionId, docId, Some(userSpecifiedDocId), Some("1.5.2.2.10"), Some("VSK.SR.Adammm"), Seq(
      -\/(("dict",  Seq("1.5.2.2.10.1"))),
      -\/(("lemma", Seq("1.5.2.2.10.3.3"))),
      -\/(("tr",    Seq("1.5.2.2.10.9.3.3")))))
      assertAdd(expectMsgType[Add])(indexName, collectionPath, collectionId, docId, Some(userSpecifiedDocId), Some("1.5.2.2.12"), Some("VSK.SR.Adammmm"), Seq(
      -\/(("dict",  Seq("1.5.2.2.12.1"))),
      -\/(("lemma", Seq("1.5.2.2.12.3.3"))),
      -\/(("tr",    Seq("1.5.2.2.12.9.3.3")))))
      expectMsg(FinishDocument(indexName, Some(userSpecifiedDocId), collectionId, docId))
    }

    "produce the correct actor messages for a object based index config with just text nodes in data" in new AkkaTestkitSpecs2Support {

      val indexName = "raskovnik-test-integration-object-based-text-nodes"
      val userSpecifiedDocId = "MZ.RGJS"
      val testCollectionPath = XmldbURI.create("/db/test-integration-object-based-text-nodes")

      // register our index
      implicit val brokerPool = getBrokerPool
      val algoliaIndex = createAndRegisterAlgoliaIndex(system, Some(testActor))

      // set up an index configuration
      storeCollectionConfig(algoliaIndex, testCollectionPath, getTestResource("integration/object-based-text-nodes/collection.xconf"))

      // store some data (which will be indexed)
      val (collectionId, docId) = storeTestDocument(algoliaIndex, testCollectionPath, getTestResource("integration/object-based-text-nodes/MZ.RGJS.xml"))

      collectionId mustNotEqual -1
      docId mustNotEqual -1

      val collectionPath = testCollectionPath.getRawCollectionPath

      expectMsg(Authentication("some-application-id", "some-admin-api-key"))
      expectMsg(StartDocument(indexName, collectionId, docId))
      assertAdd(expectMsgType[Add])(indexName, collectionPath, collectionId, docId, Some(userSpecifiedDocId), Some("4.6.2.2.4"), Some("MZ.RGJS.наводаџија"), Seq(
        \/-(("e-e",  Seq("4.6.2.2.4.7")))))
      expectMsg(FinishDocument(indexName, Some(userSpecifiedDocId), collectionId, docId))
    }
  }



  type NameAndValueIds = (Name, Seq[String])
  type IndexableAttributeNameAndValueIds = NameAndValueIds
  type IndexableObjectNameAndValueIds = NameAndValueIds

  private def assertAdd(addMsg: Add)(indexName: IndexName, collectionPath: CollectionPath, collectionId: CollectionId, documentId: DocumentId, userSpecifiedDocumentId: Option[UserSpecifiedDocumentId], nodeId: Option[String], userSpecifiedNodeId: Option[UserSpecifiedNodeId], children: Seq[IndexableAttributeNameAndValueIds \/ IndexableObjectNameAndValueIds]) = {
    addMsg.indexName mustEqual indexName
    addMsg.indexableRootObject.collectionPath mustEqual collectionPath
    addMsg.indexableRootObject.collectionId mustEqual collectionId
    addMsg.indexableRootObject.documentId mustEqual documentId
    addMsg.indexableRootObject.userSpecifiedDocumentId mustEqual userSpecifiedDocumentId
    addMsg.indexableRootObject.nodeId mustEqual nodeId
    addMsg.indexableRootObject.userSpecifiedNodeId mustEqual userSpecifiedNodeId

    val actualChildren: Seq[IndexableAttributeNameAndValueIds \/ IndexableObjectNameAndValueIds] = addMsg.indexableRootObject.children.map(_.map(indexableObject => (indexableObject.name, indexableObject.values.map(_.id))).leftMap(indexableAttribute => (indexableAttribute.name, indexableAttribute.values.map(_.id))))
    actualChildren mustEqual children
  }

  private def createAndRegisterAlgoliaIndex(system: ActorSystem, incrementalIndexingManagerActor: Option[ActorRef])(implicit brokerPool: BrokerPool): AlgoliaIndex = {
    val dataDir = brokerPool.getConfiguration.getProperty(ScalaBrokerPoolBridge.PROPERTY_DATA_DIR).asInstanceOf[Path]
    val indexModuleConfigElem = mockIndexModuleConfig()
    val algoliaIndex = new AlgoliaIndex(Some(system), incrementalIndexingManagerActor)
    algoliaIndex.configure(brokerPool, dataDir, indexModuleConfigElem)
    brokerPool.getIndexManager.registerIndex(algoliaIndex)
    algoliaIndex
  }

  private def storeCollectionConfig(algoliaIndex: AlgoliaIndex, testCollectionPath: XmldbURI, collectionXconfFile: Path)(implicit brokerPool: BrokerPool) {
    val collectionConf = new FileInputSource(collectionXconfFile)
    withBroker { broker =>
      withTxn { txn =>
        injectAlgoliaIndexWorkerIfNotPresent(broker, algoliaIndex)

        val collection = broker.getOrCreateCollection(txn, XmldbURI.CONFIG_COLLECTION_URI.append(testCollectionPath))
        broker.saveCollection(txn, collection)

        val indexInfo = collection.validateXMLResource(txn, broker, CollectionConfiguration.DEFAULT_COLLECTION_CONFIG_FILE_URI, collectionConf)
        collection.store(txn, broker, indexInfo, collectionConf)
        //collection.close()
      }
    }
  }

  private def storeTestDocument(algoliaIndex: AlgoliaIndex, testCollectionPath: XmldbURI, documentFile: Path)(implicit brokerPool: BrokerPool): (Int, Int) = {
    val dataFile = new FileInputSource(documentFile)
    withBroker { broker =>
      withTxn { txn =>

        injectAlgoliaIndexWorkerIfNotPresent(broker, algoliaIndex)

        val collection = broker.getOrCreateCollection(txn, testCollectionPath)
        broker.saveCollection(txn, collection)
        val collectionId = collection.getId

        val indexInfo = collection.validateXMLResource(txn, broker, XmldbURI.create("VSK.TEST.xml"), dataFile)
        collection.store(txn, broker, indexInfo, dataFile)
        val docId = indexInfo.getDocument.getDocId
        //collection.close()

        (collectionId, docId)
      }
    }.flatMap(identity) match {
      case -\/(e) =>
        throw e
      case \/-(result) =>
        result
    }
  }

  private def mockIndexModuleConfig() : Element = {
    import org.easymock.EasyMock._
    val element = mock(classOf[org.w3c.dom.Element])

    expect(element.getAttribute("application-id")).andReturn("some-application-id")
    expect(element.getAttribute("admin-api-key")).andReturn("some-admin-api-key")
    expect(element.hasAttribute("id")).andReturn(true)
    expect(element.getAttribute("id")).andReturn("algolia-index")

    replay(element)

    element
  }

  private def injectAlgoliaIndexWorkerIfNotPresent(broker: DBBroker, algoliaIndex: AlgoliaIndex) = {
    val field = broker.getIndexController.getClass.getDeclaredField("indexWorkers")
    field.setAccessible(true)
    val indexWorkers = field.get(broker.getIndexController).asInstanceOf[java.util.Map[String, IndexWorker]]

    if (!indexWorkers.containsKey(AlgoliaIndex.ID)) {
      val algoliaIndexWorker = algoliaIndex.getWorker(broker)
      indexWorkers.put(AlgoliaIndex.ID, algoliaIndexWorker)
    }
  }
}
