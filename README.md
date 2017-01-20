# еХistential Indexer for Algolia

eXistential Indexer for Algolia is a configurable plug-in for the native XML database [eXist-db](https://github.com/eXist-db/exist). It uses eXist's own indexing mechanisms to create, upload and incrementally sync local indexes with [Algolia's cloud services](http://www.algolia.com). 



## Installation

It's probably a good idea to start with a clean database, which means a completely clean `$EXIST_HOME/webapp/WEB-INF/data` folder.

TODO: WHICH VERSION OF EXIST? NOT YET IN THE DEV BRANCH? SHOULD WE WAIT?

TODO: Compile (XXXX) or download (XXXX) the plugin

- Make sure eXist is not running

- Put this jar file into eXist's lib/user folder:
`http://static.adamretter.org.uk/exist-algolia-index-assembly-1.0.jar` TODO: THIS WILL BE A DIFFERENT LINK

- Modify eXist's conf.xml file by adding the following line to the `indexer/modules` section:

~~~	xml
<module id="algolia-index"
class="org.humanistika.exist.index.algolia.AlgoliaIndex"
application-id="YOUR-ALGOLIA-APPLICATION-ID"
admin-api-key="YOUR-ALGOLIA-ADMIN-API-KEY"/>
~~~	

- Startup eXist.

- For the collection that you want to index with Algolia, you need to add an algolia index configuration to eXists' collection.xconf file. See [instructions](#collectionconf). 

- You can then reindex the collection with the data in eXist the way you normally do, and it should be added to Algolia. 

You can use the Algolia Dashboard to examine the index. Alternatively, you can also set up eXist to to log changes to our Algolia Index. See [instructions](#logging). 

<a name="collectionconf">
### Configuration
</a>

For a single collection in eXist, you can put data into one or more indexes in Algolia, just create an "index" element inside the "algolia" element for each index and give it the name of the Algolia index, if the index doesn't exist in Algolia it will be automatically created for you.

For incremental indexing to work, you need to have two sets of unique ids, one for each document in the collection (documentId) and one for each rootObject (nodeId). 

~~~	xml
<collection xmlns="http://exist-db.org/collection-config/1.0">
   <index>

       <algolia>
       <namespaceMappings>
            <namespaceMapping>
                <prefix>xml</prefix>
                    <namespace>http://www.w3.org/XML/1998/namespace</namespace>
                </namespaceMapping>
            </namespaceMappings>
           <index name="my-algolia-index-1" documentId="/path/to/unique-id/@хml:id">
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
~~~	


A "rootObject" is equivalent to an object inside an Algolia Index. We create one "rootObject" either for each document, or document fragment (if you specify a path attribute on the rootObject).

An "attribute" (represents a JSON object attribute, not to be confused with an XML attribute) is a simple key/value pair that is extracted from the XML and placed into the Algolia object ("rootObject" as we call it). All of the text nodes or attribute values indicated by the "path" on the "attribute" element will be serialized to a string (and then converted if you set an explicit "type" attribute).

The path for an "attribute" may point to either an XML element or XML attribute node. Paths must be simple, you can use namespace prefixes in the path, but you must also set the namespaceMappings element in the collection.xconf (see the attached schema).

TODO: link to schema when Adam uploads the files. 

An "object" represents a JSON object, and this is where things become fun, we basically serialize the XML node pointed to by the "path" attribute on the "object" element to a JSON equivalent. This allows you to create highly complex and structured objects in the Algolia index from your XML.

The "name" attribute that is available on the "attribute" and "object" elements allows you to set the name of the field in the JSON object of the Algolia index, this means that name names of your data fields can be different in Algolia to eXist if you wish.

<a name="logging">
## Enable logging in eXist (optional)
</a>

You can see what we are sending to Algolia by adding the following to your `$EXIST_HOME/log4j2.xml` file:

Add this as a child of the `<Appenders>` element:

~~~	xml
<RollingRandomAccessFile name="algolia.index"
filePattern="${logs}/expath-repo.${rollover.file.pattern}.log.gz"
fileName="${logs}/algolia-index.log">
   <Policies>
       <SizeBasedTriggeringPolicy size="${rollover.max.size}"/>
   </Policies>
   <DefaultRolloverStrategy max="${rollover.max}"/>
   <PatternLayout pattern="${exist.file.pattern}"/>
</RollingRandomAccessFile>
~~~

And add this as a child of the `<Loggers>` element:

~~~	xml
<Logger name="org.humanistika.exist.index.algolia" additivity="false"
level="trace">
   <AppenderRef ref="algolia.index"/>
</Logger>
~~~	

The log output will then appear in
`$EXIST_HOME/webapp/WEB-INF/logs/algolia-index.log` the next time eXist is started.

## Current limitations

- you can't use paths with predicates in index configuration. Support for predicates will be added at a later stage.
- when you backup eXist, you should now also
make a backup copy of `$EXIST_HOME/webapp/WEB-INF/data/algolia-index` as that holds the local representation of what is on the remote Algolia Server. Support for adding locally stored Algolia indexes to the backup/restore procedure may be added in the future.

## Acknowledgements

Hats off to [Adam Retter](https://github.com/adamretter) for sharing his superb programming skills with us in this project.

This tool has been developed in the context of an ongoing BCDH project: Raskovnik - A Serbian Dictionary Platform (together with the Institute of Serbian Language). The project has received funding from the Ministry of culture and Information of the Republic of Serbia.

 