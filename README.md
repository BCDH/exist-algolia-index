# eXist-db Indexer for Algolia

![Build Status](https://github.com/BCDH/exist-algolia-index/actions/workflows/ci.yml/badge.svg) [![Scala 2.13+](https://img.shields.io/badge/scala-2.13+-dc322f.svg)](http://scala-lang.org) [![License GPL 3](https://img.shields.io/badge/license-GPL%203-blue.svg)](https://www.gnu.org/licenses/gpl-3.0.html)

eXist Indexer for Algolia is a configurable index plug-in for the [eXist-db](https://github.com/eXist-db/exist) native XML database. It uses eXist's own indexing mechanisms to create, upload and incrementally sync local indexes with [Algolia's](http://www.algolia.com) cloud services.

The current build and test baseline is **eXist-db 6.4.1**.

<p align="center">
  <img src="https://i.imgur.com/yqIlRI0.png">
  <span style="color:gray; font-size:0.8rem">eXist Indexer for Algolia in action: autocomplete search on <a href="http://raskovnik.org">http://raskovnik.org</a></span>
</p>

## Installation

This project is installed as an **eXist plugin JAR**, not as an EXPath/XAR package. The supported deployment model is:

1. build the assembly JAR
2. copy it into eXist's `lib` directory
3. ensure `conf.xml` contains the Algolia index module stanza
4. ensure `etc/startup.xml` contains the plugin dependency entry
5. restart eXist
6. verify the install and run a smoke reindex
7. reindex existing configured collections into Algolia, which `run` now does by default

The repo now includes local and staging install scripts for that flow.

### Build

Requirements: Java 17, `sbt`.

```bash
sbt assembly
```

The assembly is written to:

```bash
target/scala-2.13/exist-algolia-index-assembly-1.1.0-SNAPSHOT.jar
```

### Local eXist Install

1. Copy `.env.example` to `.env` if you want defaults loaded automatically.
2. Set at least:

```bash
EXIST_HOME=/path/to/exist-db
ALGOLIA_APPLICATION_ID=your-algolia-application-id
ALGOLIA_ADMIN_API_KEY=your-algolia-admin-api-key
EXIST_LOCAL_ADMIN_PASSWORD=your-local-admin-password
```

Optional local overrides:

```bash
EXIST_CLIENT_CMD=/path/to/exist-db/bin/client.sh
EXIST_CONF_XML=/path/to/exist-db/etc/conf.xml
EXIST_STARTUP_XML=/path/to/exist-db/etc/startup.xml
EXIST_PLUGIN_LIB_DIR=/path/to/exist-db/lib
EXIST_RESTART_CMD="/path/to/exist-db/bin/startup.sh restart"
EXIST_REINDEX_COLLECTION=/db/dictionaries/vuk
ALGOLIA_SMOKE_INDEX_NAME=exist-algolia-index-smoke
```

Run the full local flow:

```bash
./scripts/exist-local.sh run
```

Set the default reindex target in `.env`:

```bash
EXIST_REINDEX_COLLECTION=/db/dictionaries/vuk
```

Then run without parameters:

```bash
./scripts/exist-local.sh run
```

Override that default once from the command line:

```bash
./scripts/exist-local.sh run /db/dictionaries/vuk
```

Skip the post-install reindex explicitly:

```bash
./scripts/exist-local.sh run --skip-reindex
```

Useful subcommands:

```bash
./scripts/exist-local.sh build
./scripts/exist-local.sh install-plugin
./scripts/exist-local.sh configure-plugin
./scripts/exist-local.sh configure-startup
./scripts/exist-local.sh reindex-collection /db/my-collection
./scripts/exist-local.sh restart
./scripts/exist-local.sh verify
```

Notes:

- `run` will build, install, patch `conf.xml`, patch `startup.xml`, restart eXist if `EXIST_RESTART_CMD` is configured, and then verify the install.
- after a successful `run`, the script reindexes `/db` by default so existing configured collections are backfilled into Algolia.
- set `EXIST_REINDEX_COLLECTION=/db/my-collection` or pass `/db/my-collection` to `run` if you want a narrower default target than `/db`.
- pass `--skip-reindex` only if you explicitly do not want the post-install backfill step.
- if `EXIST_RESTART_CMD` is not configured, `run` stops after preparing the files and exits with a clear “restart required before verification” message.
- `verify` performs a smoke reindex using the admin account, so `EXIST_LOCAL_ADMIN_PASSWORD` is required.
- the smoke check verifies both the local store and the remote Algolia upload, then removes the temporary smoke resources from eXist and deletes the temporary Algolia index before the default backfill step can run.
- `reindex-collection /db/my-collection` is the real backfill step for collections that already contain data before the plugin was installed.

### Staging Deploy

The staging flow assumes a Dockerized eXist instance on the remote host and copies the plugin JAR into the running container, patches the config files there, restarts the container, and verifies the result.

Important for the official `existdb/existdb` image:

- it starts from `exist.uber.jar`
- `startup.xml` alone does not make a custom plugin JAR visible on the runtime classpath
- the container must start with the plugin JAR already present and included in `CLASSPATH`

Example container startup shape:

```bash
docker run \
  -e CLASSPATH=/exist/lib/exist.uber.jar:/exist/lib/exist-algolia-index-assembly-1.1.0-SNAPSHOT.jar \
  -v "$(pwd)/target/scala-2.13/exist-algolia-index-assembly-1.1.0-SNAPSHOT.jar:/exist/lib/exist-algolia-index-assembly-1.1.0-SNAPSHOT.jar" \
  existdb/existdb:release
```

The staging verification now checks for that when it detects an `exist.uber.jar` container.

Set at least:

```bash
EXIST_STAGE_HOST=staging-host.example.org
EXIST_STAGE_SSH_USER=deploy-user
EXIST_STAGE_ADMIN_PASSWORD=your-staging-admin-password
ALGOLIA_APPLICATION_ID=your-algolia-application-id
ALGOLIA_ADMIN_API_KEY=your-algolia-admin-api-key
```

Optional staging overrides:

```bash
EXIST_STAGE_PORT=22
EXIST_STAGE_REMOTE_DIR=/tmp/exist-algolia-stage
EXISTDB_CONTAINER_NAME=existdb-stage
EXIST_STAGE_CONF_XML=/exist/etc/conf.xml
EXIST_STAGE_STARTUP_XML=/exist/etc/startup.xml
EXIST_STAGE_PLUGIN_LIB_DIR=/exist/lib
EXIST_STAGE_RESTART_CMD="docker restart existdb-stage"
EXIST_REINDEX_COLLECTION=/db/dictionaries/vuk
ALGOLIA_SMOKE_INDEX_NAME=exist-algolia-index-smoke
```

Run the full staging flow from this checkout:

```bash
./scripts/exist-stage.sh run
```

Set the default reindex target in `.env`:

```bash
EXIST_REINDEX_COLLECTION=/db/dictionaries/vuk
```

Then run without parameters:

```bash
./scripts/exist-stage.sh run
```

Override that default once from the command line:

```bash
./scripts/exist-stage.sh run /db/dictionaries/vuk
```

Skip the post-install reindex explicitly:

```bash
./scripts/exist-stage.sh run --skip-reindex
```

Useful subcommands:

```bash
./scripts/exist-stage.sh build
./scripts/exist-stage.sh upload --skip-build
./scripts/exist-stage.sh deploy --skip-build
./scripts/exist-stage.sh reindex-collection /db/my-collection
```

Notes:

- the remote helper is `./scripts/exist-stage-remote.sh`; `exist-stage.sh` uploads it automatically and executes it over SSH
- after a successful `run`, the remote helper reindexes `/db` by default so existing configured collections are backfilled into Algolia
- set `EXIST_REINDEX_COLLECTION=/db/my-collection` or pass `/db/my-collection` to `run` if you want a narrower default target than `/db`
- pass `--skip-reindex` only if you explicitly do not want the post-install backfill step
- if `EXIST_STAGE_RESTART_CMD` is unset, the remote helper defaults to `docker restart ${EXISTDB_CONTAINER_NAME}`
- the staging smoke check verifies both the local store and the remote Algolia upload, then removes the temporary smoke resources from eXist and deletes the temporary Algolia index before the default backfill step can run
- `reindex-collection /db/my-collection` is the real backfill step for collections that already exist in the database

### Backfill Existing Data

Installing the plugin does not automatically upload documents that were already present in eXist before installation unless you run a reindex. The default `run` workflow now performs that reindex automatically after a successful install and smoke verification. The temporary smoke collection used by `verify` is cleaned up before that backfill runs.

By default the reindex target is `/db`. In normal use, set `EXIST_REINDEX_COLLECTION` in `.env` to the collection you want to backfill. Pass the collection path directly to `run` only as a one-off override.

Local example:

```bash
./scripts/exist-local.sh run /db/dictionaries/vuk
```

Staging example:

```bash
./scripts/exist-stage.sh run /db/dictionaries/vuk
```

Manual backfill is still available if you want to run it separately:

```bash
./scripts/exist-local.sh reindex-collection /db/dictionaries/vuk
./scripts/exist-stage.sh reindex-collection /db/dictionaries/vuk
```

If a collection is configured with an `<algolia>` block in its `collection.xconf`, that reindex will trigger the plugin to rebuild the local store and push the corresponding records to Algolia.

### Manual install reference

If you are not using the scripts, the plugin still needs these two config entries:

`conf.xml`, inside `indexer/modules`:

```xml
<module id="algolia-index"
    class="org.humanistika.exist.index.algolia.AlgoliaIndex"
    application-id="YOUR-ALGOLIA-APPLICATION-ID"
    admin-api-key="YOUR-ALGOLIA-ADMIN-API-KEY"/>
```

`etc/startup.xml`, as a dependency entry for the exact JAR filename:

```xml
<dependency>
    <groupId>org.humanistika.exist.index.algolia</groupId>
    <artifactId>exist-algolia-index</artifactId>
    <version>1.1.0-SNAPSHOT</version>
    <relativePath>exist-algolia-index-assembly-1.1.0-SNAPSHOT.jar</relativePath>
</dependency>
```

After that, restart eXist and reindex the configured collections.

## Configuration

For a single collection in eXist, you can put data into one or more indexes in Algolia, just create an "index" element inside the "algolia" element for each index and give it the name of the Algolia index, if the index doesn't exist in Algolia it will be automatically created for you.

For incremental indexing to work, you need to have two sets of unique ids, one for each document in the collection (documentId) and one for each rootObject (nodeId).

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
            <index name="my-algolia-index-1" documentId="/path/to/unique-id/@хml:id" visibleBy="/path/to/unique-id">
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

An Optional `VisibleBy` attribute can be used to restrict data access when searching the Algolia index

A `rootObject` is equivalent to an object inside an Algolia Index. We create one "rootObject" either for each document, or document fragment (if you specify a path attribute on the rootObject).

An `attribute` (represents a JSON object attribute, not to be confused with an XML attribute) is a simple key/value pair that is extracted from the XML and placed into the Algolia object ("rootObject" as we call it). All of the text nodes or attribute values indicated by the "path" on the "attribute" element will be serialized to a string (and then converted if you set an explicit "type" attribute).

The path for an "attribute" may point to either an XML element or XML attribute node. Paths must be simple, you can use namespace prefixes in the path, but you must also set the namespaceMappings element in the collection.xconf.

The XML Schema file https://github.com/BCDH/exist-algolia-index/blob/master/src/main/resources/xsd/exist-algolia-index-config.xsd defines and documents the index configuration.

An `object` represents a JSON object, and this is where things become fun, we basically serialize the XML node pointed to by the "path" attribute on the "object" element to a JSON equivalent. This allows you to create highly complex and structured objects in the Algolia index from your XML.

The `name` attribute that is available on the "attribute" and "object" elements allows you to set the name of the field in the JSON object of the Algolia index, this means that name names of your data fields can be different in Algolia to eXist if you wish.

## Limiting Objects access to certain users

You can limit data access by setting the `visibleBy` attribute in `collection.xconf` then matching the path in your XML data preferably in the header
You can use this example from out test suit

xml: https://github.com/BCDH/exist-algolia-index/tree/master/src/test/resources/integration/user-specified-visibleBy/VSK.TEST.xml

collection.xconf https://github.com/BCDH/exist-algolia-index/tree/master/src/test/resources/integration/user-specified-visibleBy/collection.xconf

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

The log output will then appear in
`$EXIST_HOME/webapp/WEB-INF/logs/algolia-index.log` the next time eXist is started.

## Current limitations

- you can't use paths with predicates in index configuration. Support for predicates will be added at a later stage.
- when you backup eXist, you should now also
  make a backup copy of `$EXIST_HOME/webapp/WEB-INF/data/algolia-index` as that holds the local representation of what is on the remote Algolia Server. Support for adding locally stored Algolia indexes to the backup/restore procedure may be added in the future.

## Building from Source

```bash
git clone https://github.com/BCDH/exist-algolia-index.git
cd exist-algolia-index
sbt assembly
```

The assembled binary can then be found in `target/scala-2.13`.

## Acknowledgements

Hats off to [Adam Retter](https://github.com/adamretter) for sharing his superb programming skills with us in this project.

This tool has been developed in the context of an ongoing [BCDH](http://www.humanistika.org) project: Raskovnik - A Serbian Dictionary Platform (together with the Institute of Serbian Language). The project has received funding from the Ministry of Culture and Information of the Republic of Serbia.
