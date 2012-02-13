package org.example.tests.standalone;

import static org.example.tests.utils.Utils.*;

import java.io.File;
import java.io.StringWriter;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import junit.framework.TestCase;

import org.osgi.service.bindex.ResourceIndexer;
import org.osgi.service.bindex.impl.BIndex2;

public class TestStandaloneLibrary extends TestCase {

	public void testBasicServiceInvocation() throws Exception {
		ResourceIndexer indexer = new BIndex2();
		
		StringWriter writer = new StringWriter();
		File tempFile = copyToTempFile("testdata/01-bsn+version.jar");

		Map<String, String> config = new HashMap<String, String>();
		config.put(ResourceIndexer.ROOT_URL, new File("generated").getAbsoluteFile().toURL().toString());
		indexer.indexFragment(Collections.singleton(tempFile), writer, config);
		
		assertEquals(readStream(TestStandaloneLibrary.class.getResourceAsStream("/testdata/fragment-basic.txt")), writer.toString().trim());
	}

}
