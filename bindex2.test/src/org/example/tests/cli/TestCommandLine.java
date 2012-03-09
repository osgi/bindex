package org.example.tests.cli;

import static org.example.tests.utils.Utils.copyToTempFile;

import java.io.File;

import junit.framework.TestCase;

import org.osgi.impl.bundle.bindex.cli.Index;

public class TestCommandLine extends TestCase {
	
	@Override
	protected void setUp() throws Exception {
		File index = new File("index.xml");
		if (index.exists()) index.delete();
	}

	public void testCommandLine() throws Exception {
		File tempFile = copyToTempFile("testdata/01-bsn+version.jar");
		String[] args = new String[] {
				tempFile.getAbsolutePath()
		};
		Index.internalMain(args);
		
		assertTrue(new File("index.xml").exists());
	}
}
