You can find a description of the use of this program at 
http://www.osgi.org/Repository/BIndex

The repository file created by Bindex is defined in RFC 0112,
http://www2.osgi.org/download/rfc-0112_BundleRepository.pdf

This is a bndtools project, see http://njbartlett.name/bndtools.html

If you make changes and are willing to share them, you can send patches 
to Peter.Kriens@osgi.org. If you are an OSGi member, we can give you 
editing rights. All donations must be done with an Apache Software License 
2.0 grant. Please make this explicit in your donation.

You can send any other feedback to Peter.Kriens@osgi.org

OBR is currently being worked up on the OSGi Alliance which will likely change
the format of the XML.


Command Line Usage
==================

TODO

Library Usage
=============

BIndex can be used as a JAR library in a traditional Java application or web/Java EE container by adding the `org.osgi.impl.bundle.bindex2.lib.jar` to your classpath. The API is as follows:

	BIndex indexer = new BIndex();
	// optional: add one or more custom resource analyzers
	indexer.add(new MyExtenderResourceAnalyzer());

	// optional: set config params
	Map<String, String> config = new HashMap<String, String>();
	config.put(ResourceIndexer.REPOSITORY_NAME, "My Repository");

	Set<File> inputs = findInputs();
	Writer output = new FileWriter("repository.xml");
	indexer.index(inputs, output, config);