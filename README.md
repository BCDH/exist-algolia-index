# eXist-db Indexer for Algolia

![Build Status](https://github.com/BCDH/exist-algolia-index/actions/workflows/ci.yml/badge.svg) ![Java 17+](https://img.shields.io/badge/java-17%2B-007396.svg) ![eXist-db 6.4.1](https://img.shields.io/badge/eXist--db-6.4.1-6f42c1.svg) [![License GPL 3](https://img.shields.io/badge/license-GPL%203-blue.svg)](https://www.gnu.org/licenses/gpl-3.0.html)

eXist Indexer for Algolia is a configurable index plug-in for the [eXist-db](https://github.com/eXist-db/exist) native XML database. It uses eXist's own indexing mechanisms to create, upload and incrementally sync local indexes with [Algolia's](http://www.algolia.com) cloud services.

<p align="center">
  <img src="https://i.imgur.com/yqIlRI0.png">
  <span style="color:gray; font-size:0.8rem">Example deployment: autocomplete search on <a href="http://raskovnik.org">http://raskovnik.org</a></span>
</p>

## Installation

This README covers the build, manual installation, and general configuration of the plugin.

### Build

Requirements: Java 17, `sbt`.

```bash
sbt assembly
```

The assembly is written to:

```bash
target/scala-2.13/exist-algolia-index-assembly-1.1.2.jar
```

### Manual install

The plugin JAR must be built and then installed into eXist manually.

1. Build the assembly:

   ```bash
   sbt assembly
   ```

2. Copy the resulting JAR into eXist's plugin/library directory.

3. Add the Algolia module to `conf.xml` inside `indexer/modules`:

   ```xml
   <module id="algolia-index"
       class="org.humanistika.exist.index.algolia.AlgoliaIndex"
       application-id="YOUR-ALGOLIA-APPLICATION-ID"
       admin-api-key="YOUR-ALGOLIA-ADMIN-API-KEY"
       batch-size="1000"/>
   ```

4. Add the dependency entry to `startup.xml`:

   ```xml
   <dependency>
       <groupId>org.humanistika.exist.index.algolia</groupId>
       <artifactId>exist-algolia-index</artifactId>
       <version>1.1.2</version>
       <relativePath>exist-algolia-index-assembly-1.1.2.jar</relativePath>
   </dependency>
   ```

5. Restart eXist.

6. Reindex the configured collections so already-present data is pushed into Algolia.
   The correct reindex target depends on your own collection structure. Reindex the collection or subcollection whose `collection.xconf` contains the Algolia index configuration.

## Configuration

For a single collection in eXist, you can put data into one or more indexes in Algolia, just create an "index" element inside the "algolia" element for each index and give it the name of the Algolia index, if the index doesn't exist in Algolia it will be automatically created for you.

For incremental indexing to work, you need to have two sets of unique ids, one for each document in the collection (documentId) and one for each rootObject (nodeId).

Algolia writes are sent in batches. The global `batch-size` module attribute defaults to `1000` operations per request. A collection-level `<index>` can override it with `batchSize` if a specific Algolia index needs smaller or larger chunks.

```xml
<collection xmlns="http://exist-db.org/collection-config/1.0">
    <index>
        <algolia>
            <namespaceMappings>
                <namespaceMapping>
                    <prefix>xml</prefix>
                    <namespace>http://www.w3.org/XML/1998/namespace</namespace>
                </namespaceMapping>
            </namespaceMappings>
            <index name="my-algolia-index-1" documentId="/path/to/unique-id/@xml:id" visibleBy="/path/to/unique-id" batchSize="1000">
                <rootObject path="/path/to/element" nodeId="@xml:id">
                    <attribute name="f1" path="/further/patha"/>
                    <attribute name="f2" path="/further/pathb" type="integer"/>
                    <object name="other" path="/further/pathc">
                        <map path="/x" type="boolean"/>
                   </object>
                </rootObject>
            </index>
        </algolia>
    </index>
</collection>
```

An optional `visibleBy` attribute can be used to restrict data access when searching the Algolia index.

A `rootObject` is equivalent to an object inside an Algolia Index. We create one "rootObject" either for each document, or document fragment (if you specify a path attribute on the rootObject).

An `attribute` (represents a JSON object attribute, not to be confused with an XML attribute) is a simple key/value pair that is extracted from the XML and placed into the Algolia object ("rootObject" as we call it). All of the text nodes or attribute values indicated by the "path" on the "attribute" element will be serialized to a string (and then converted if you set an explicit "type" attribute).

The path for an "attribute" may point to either an XML element or XML attribute node. Paths must be simple, you can use namespace prefixes in the path, but you must also set the namespaceMappings element in the `collection.xconf`.

The XML Schema file [exist-algolia-index-config.xsd](https://github.com/BCDH/exist-algolia-index/blob/master/src/main/resources/xsd/exist-algolia-index-config.xsd) defines and documents the index configuration.

An `object` represents a JSON object, and this is where things become fun, we basically serialize the XML node pointed to by the "path" attribute on the "object" element to a JSON equivalent. This allows you to create highly complex and structured objects in the Algolia index from your XML.

The `name` attribute that is available on the "attribute" and "object" elements allows you to set the name of the field in the JSON object of the Algolia index, this means that name names of your data fields can be different in Algolia to eXist if you wish.

### Reindexing Existing Data

Installing or updating the plugin does not by itself upload already-present XML documents to Algolia. After installation, reindex each configured collection in eXist so the configured `rootObject`s are serialized and pushed to Algolia.

In general:

- reindex the full configured collection for a first-time backfill
- reindex a narrower subcollection if your deployment replaced only part of the XML corpus and that subcollection has the relevant Algolia collection config
- avoid reindexing broad parent collections unless they are the intended scope of the Algolia configuration

### Limiting Object Access

You can limit data access by setting the `visibleBy` attribute in `collection.xconf` and mapping it to the corresponding path in your XML data, preferably in the document header.

See the test fixture examples:

- XML: [VSK.TEST.xml](https://github.com/BCDH/exist-algolia-index/tree/master/src/test/resources/integration/user-specified-visibleBy/VSK.TEST.xml)
- Configuration: [collection.xconf](https://github.com/BCDH/exist-algolia-index/tree/master/src/test/resources/integration/user-specified-visibleBy/collection.xconf)

## Enable logging in eXist (optional)

You can see what we are sending to Algolia by adding the following to your `$EXIST_HOME/log4j2.xml` file:

Add this as a child of the `<Appenders>` element:

```xml
<RollingRandomAccessFile name="algolia.index"
        filePattern="${logs}/algolia-index.${rollover.file.pattern}.log.gz"
        fileName="${logs}/algolia-index.log">
    <Policies>
        <SizeBasedTriggeringPolicy size="${rollover.max.size}"/>
    </Policies>
    <DefaultRolloverStrategy max="${rollover.max}"/>
    <PatternLayout pattern="${exist.file.pattern}"/>
</RollingRandomAccessFile>
```

And add this as a child of the `<Loggers>` element:

```xml
<Logger name="org.humanistika.exist.index.algolia" additivity="false" level="trace">
    <AppenderRef ref="algolia.index"/>
</Logger>
```

The log output will then appear in eXist's configured log directory, usually `logs/algolia-index.log` under the active eXist home or container layout, the next time eXist is started.

## Current limitations

When you back up eXist, you should also back up the `algolia-index` directory inside eXist's configured data directory, because it holds the local representation of what is stored on the remote Algolia server. Support for integrating that local store into a native backup/restore workflow may be added later.

## Acknowledgements

Hats off to [Adam Retter](https://github.com/adamretter) for sharing his superb programming skills with us in this project.

This tool was developed in the context of ongoing work at [BCDH](http://www.humanistika.org), including Raskovnik, a Serbian dictionary platform built together with the Institute of Serbian Language.
