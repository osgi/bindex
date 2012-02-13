package org.example.tests.utils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;

public class Utils {
	
	public static final String readStream(InputStream stream) throws IOException {
		InputStreamReader reader = new InputStreamReader(stream);
		StringBuilder result = new StringBuilder();
		
		char[] buf = new char[1024];
		int charsRead = reader.read(buf, 0, buf.length);
		while (charsRead > -1) {
			result.append(buf, 0, charsRead);
			charsRead = reader.read(buf, 0, buf.length);
		}
		
		return result.toString();
	}
	
	public static final void copyFully(InputStream input, OutputStream output) throws IOException {
		try {
			byte[] buf = new byte[1024];
			int bytesRead;
			while (true) {
				if ((bytesRead = input.read(buf, 0, 1024)) < 0)
					break;
				output.write(buf, 0, bytesRead);
			}
		} finally {
			try { input.close(); } catch (IOException e) { /* ignore */ }
			try { output.close(); } catch (IOException e) { /* ignore */ }
		}
	}
	
	public static File copyToTempFile(String resourcePath) throws IOException {
		File tempFile = new File(new File("generated"), resourcePath);
		tempFile.getParentFile().mkdirs();
		
		Utils.copyFully(Utils.class.getResourceAsStream("/" + resourcePath), new FileOutputStream(tempFile));
		return tempFile;
	}

}
