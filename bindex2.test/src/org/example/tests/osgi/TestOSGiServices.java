package org.example.tests.osgi;

import static org.example.tests.utils.Utils.*;

import java.io.File;
import java.io.StringWriter;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import junit.framework.TestCase;

import org.example.tests.utils.WibbleAnalyzer;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.bindex.ResourceAnalyzer;
import org.osgi.service.bindex.ResourceIndexer;

public class TestOSGiServices extends TestCase {

	private final BundleContext context = FrameworkUtil.getBundle(this.getClass()).getBundleContext();
	
	public void testBasicServiceInvocation() throws Exception {
		ServiceReference ref = context.getServiceReference(ResourceIndexer.class.getName());
		ResourceIndexer indexer = (ResourceIndexer) context.getService(ref);
		
		StringWriter writer = new StringWriter();
		
		Map<String, String> config = new HashMap<String, String>();
		config.put(ResourceIndexer.ROOT_URL, new File("generated").getAbsoluteFile().toURL().toString());
		indexer.indexFragment(Collections.singleton(copyToTempFile("testdata/01-bsn+version.jar")), writer, config);
		
		assertEquals(readStream(TestOSGiServices.class.getResourceAsStream("/testdata/fragment-basic.txt")), writer.toString().trim());
		
		context.ungetService(ref);
	}
	
	public void testWhiteboardAnalyzer() throws Exception {
		ServiceRegistration reg = context.registerService(ResourceAnalyzer.class.getName(), new WibbleAnalyzer(), null);
		
		ServiceReference ref = context.getServiceReference(ResourceIndexer.class.getName());
		ResourceIndexer indexer = (ResourceIndexer) context.getService(ref);
		StringWriter writer = new StringWriter();
		
		Map<String, String> config = new HashMap<String, String>();
		config.put(ResourceIndexer.ROOT_URL, new File("generated").getAbsoluteFile().toURL().toString());
		indexer.indexFragment(Collections.singleton(copyToTempFile("testdata/01-bsn+version.jar")), writer, config);
		
		assertEquals(readStream(TestOSGiServices.class.getResourceAsStream("/testdata/fragment-wibble.txt")), writer.toString().trim());
		
		context.ungetService(ref);
		reg.unregister();
	}
	
	public void testWhiteboardAnalyzerWithFilter() throws Exception {
		Properties analyzerProps = new Properties();
		analyzerProps.put(ResourceAnalyzer.FILTER, "(location=*sion.jar)");
		ServiceRegistration reg = context.registerService(ResourceAnalyzer.class.getName(), new WibbleAnalyzer(), analyzerProps);
		
		ServiceReference ref = context.getServiceReference(ResourceIndexer.class.getName());
		ResourceIndexer indexer = (ResourceIndexer) context.getService(ref);
		StringWriter writer = new StringWriter();
		
		Set<File> files = new LinkedHashSet<File>();
		files.add(copyToTempFile("testdata/01-bsn+version.jar"));
		files.add(copyToTempFile("testdata/02-localization.jar"));
		
		Map<String, String> config = new HashMap<String, String>();
		config.put(ResourceIndexer.ROOT_URL, new File("generated").getAbsoluteFile().toURL().toString());
		indexer.indexFragment(files, writer, config);
		
		assertEquals(readStream(TestOSGiServices.class.getResourceAsStream("/testdata/fragment-wibble-filtered.txt")), writer.toString().trim());
		
		context.ungetService(ref);
		reg.unregister();
	}
	
}
