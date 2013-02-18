package org.osgi.service.indexer.impl;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import org.osgi.framework.Constants;
import org.osgi.framework.Version;
import org.osgi.service.indexer.Builder;
import org.osgi.service.indexer.Capability;
import org.osgi.service.indexer.Namespaces;
import org.osgi.service.indexer.Requirement;
import org.osgi.service.indexer.Resource;
import org.osgi.service.indexer.ResourceAnalyzer;
import org.osgi.service.indexer.impl.types.SymbolicName;
import org.osgi.service.indexer.impl.types.VersionKey;
import org.osgi.service.indexer.impl.types.VersionRange;
import org.osgi.service.indexer.impl.util.Hex;
import org.osgi.service.indexer.impl.util.OSGiHeader;
import org.osgi.service.indexer.impl.util.Yield;
import org.osgi.service.log.LogService;

class BundleAnalyzer implements ResourceAnalyzer {
	
	private static final String SHA_256 = "SHA-256";

	// Duplicate these constants here to avoid a compile-time dependency on OSGi R4.3
	private static final String PROVIDE_CAPABILITY = "Provide-Capability";
	private static final String REQUIRE_CAPABILITY = "Require-Capability";

	// The mime-type of an OSGi bundle
	private static final String MIME_TYPE_OSGI_BUNDLE = "application/vnd.osgi.bundle";
	
	private final ThreadLocal<GeneratorState> state = new ThreadLocal<GeneratorState>();
	private final LogService log;

	public BundleAnalyzer(LogService log) {
		this.log = log;
	}

	public void analyzeResource(Resource resource, List<Capability> capabilities, List<Requirement> requirements) throws Exception {
		doIdentity(resource, capabilities);
		doContent(resource, capabilities);
		doBundleAndHost(resource, capabilities);
		doExports(resource, capabilities);
		doImports(resource, requirements);
		doRequireBundles(resource, requirements);
		doFragment(resource, requirements);
		doExportService(resource, capabilities);
		doImportService(resource, requirements);
		doBREE(resource, requirements);
		doCapabilities(resource, capabilities);
		doRequirements(resource, requirements);
	}	

	private void doIdentity(Resource resource, List<? super Capability> caps) throws Exception {
		Manifest manifest = resource.getManifest();
		if (manifest == null)
			throw new IllegalArgumentException("Missing bundle manifest.");
		
		Attributes attribs = manifest.getMainAttributes();
		String fragmentHost = attribs.getValue(Constants.FRAGMENT_HOST);
		String identity = (fragmentHost == null) ? Namespaces.RESOURCE_TYPE_BUNDLE : Namespaces.RESOURCE_TYPE_FRAGMENT;
		
		SymbolicName bsn = Util.getSymbolicName(resource);
		boolean singleton = Boolean.TRUE.toString().equalsIgnoreCase(bsn.getAttributes().get(Constants.SINGLETON_DIRECTIVE + ":"));
		
		Version version = Util.getVersion(resource);
		
		Builder builder = new Builder()
				.setNamespace(Namespaces.NS_IDENTITY)
				.addAttribute(Namespaces.NS_IDENTITY, bsn.getName())
				.addAttribute(Namespaces.ATTR_IDENTITY_TYPE, identity)
				.addAttribute(Namespaces.ATTR_VERSION, version);
		if (singleton)
			builder.addDirective(Namespaces.DIRECTIVE_SINGLETON, Boolean.TRUE.toString());
		caps.add(builder.buildCapability());
	}

	void setStateLocal(GeneratorState state) {
		this.state.set(state);
	}
	
	private GeneratorState getStateLocal() {
		return state.get();
	}
	
	private void doContent(Resource resource, List<? super Capability> capabilities) throws Exception {
		Builder builder = new Builder()
			.setNamespace(Namespaces.NS_CONTENT);
		
		String sha = calculateSHA(resource);
		builder.addAttribute(Namespaces.NS_CONTENT, sha);
		
		String location = calculateLocation(resource);
		builder.addAttribute(Namespaces.ATTR_CONTENT_URL, location);

		long size = resource.getSize();
		if (size > 0L) builder.addAttribute(Namespaces.ATTR_CONTENT_SIZE, size);
		
		builder.addAttribute(Namespaces.ATTR_CONTENT_MIME, MIME_TYPE_OSGI_BUNDLE);
		
		capabilities.add(builder.buildCapability());
	}
	
	private String calculateSHA(Resource resource) throws IOException, NoSuchAlgorithmException {
		MessageDigest digest = MessageDigest.getInstance(SHA_256);
		byte[] buf = new byte[1024];
		
		InputStream stream = null;
		try {
			stream = resource.getStream();
			while (true) {
				int bytesRead = stream.read(buf, 0, 1024);
				if (bytesRead < 0)
					break;
				
				digest.update(buf, 0, bytesRead);
			}
		} finally {
			if (stream != null) stream.close();
		}
		
		return Hex.toHexString(digest.digest());
	}

	private String calculateLocation(Resource resource) throws IOException {
		String location = resource.getLocation();
		
		File path = new File(location);
		String fileName = path.getName();
		String dir = path.getAbsoluteFile().getParentFile().toURI().toURL().toString();
		
		String result = location;
		
		GeneratorState state = getStateLocal();
		if (state != null) {
			String rootUrl = state.getRootUrl().toString();
			if (!rootUrl.endsWith("/"))
				rootUrl += "/";
			
			if (rootUrl != null) {
				if (dir.startsWith(rootUrl))
					dir = dir.substring(rootUrl.length());
				else
					throw new IllegalArgumentException("Cannot index files above the root URL.");
			}
			
			String urlTemplate = state.getUrlTemplate();
			if (urlTemplate != null) {
				result = urlTemplate.replaceAll("%s", Util.getSymbolicName(resource).getName());
				result = result.replaceAll("%f", fileName);
				result = result.replaceAll("%p", dir);
				result = result.replaceAll("%v", "" + Util.getVersion(resource));
			} else {
				result = dir + fileName;
			}
		}
		
		return result;
	}


	private static String translate(String value, Properties localStrings) {
		if (value == null)
			return null;
		
		if (!value.startsWith("%"))
			return value;
		
		value = value.substring(1);
		return localStrings.getProperty(value, value);
	}

	private static Properties loadLocalStrings(Resource resource) throws IOException {
		Properties props = new Properties();
		
		Attributes attribs = resource.getManifest().getMainAttributes();
		String path = attribs.getValue(Constants.BUNDLE_LOCALIZATION);
		if (path == null)
			path = Constants.BUNDLE_LOCALIZATION_DEFAULT_BASENAME;
		path += ".properties";
		
		Resource propsResource = resource.getChild(path);
		if (propsResource != null) {
			try {
				props.load(propsResource.getStream());
			} finally {
				propsResource.close();
			}
		}
		
		return props;
	}
	
	/**
	 * Returns an osgi.ee capability name of bree
	 * See http://www.osgi.org/Specifications/Reference
	 * 
	 * @param bree Bundle-RequiredExecutionEnvironment
	 * @return Name of RequiredExecutionEnvironment
	 */
	private static String getBREEName(String bree){
		if(bree!=null){
			return bree.replaceAll("-[0-9]+\\.[0-9]+", "");
		} else {
			return null;
		}
	}
	
	/**
	 * Returns the version number of bree
	 * @param bree Bundle-RequiredExecutionEnvironment
	 * @return associated version number
	 */
	private static String getBREEVersion(String bree){
		if(bree!=null){
			return bree.substring(bree.lastIndexOf("-")+1);
		} else {
			return null;
		}
	}

	private void doBundleAndHost(Resource resource, List<? super Capability> caps) throws Exception {
		Builder bundleBuilder = new Builder().setNamespace(Namespaces.NS_WIRING_BUNDLE);
		Builder hostBuilder   = new Builder().setNamespace(Namespaces.NS_WIRING_HOST);
		boolean allowFragments = true;
		
		Attributes attribs = resource.getManifest().getMainAttributes();
		if (attribs.getValue(Constants.FRAGMENT_HOST) != null)
			return;
		
		SymbolicName bsn = Util.getSymbolicName(resource);
		Version version = Util.getVersion(resource);
		
		bundleBuilder.addAttribute(Namespaces.NS_WIRING_BUNDLE, bsn.getName())
			.addAttribute(Constants.BUNDLE_VERSION_ATTRIBUTE, version);
		hostBuilder.addAttribute(Namespaces.NS_WIRING_HOST, bsn.getName())
			.addAttribute(Constants.BUNDLE_VERSION_ATTRIBUTE, version);
		
		for (Entry<String, String> attribEntry : bsn.getAttributes().entrySet()) {
			String key = attribEntry.getKey();
			if (key.endsWith(":")) {
				String directiveName = key.substring(0, key.length() - 1);
				if (Constants.FRAGMENT_ATTACHMENT_DIRECTIVE.equalsIgnoreCase(directiveName)) {
					if (Constants.FRAGMENT_ATTACHMENT_NEVER.equalsIgnoreCase(attribEntry.getValue()))
						allowFragments = false;
				} else if (!Constants.SINGLETON_DIRECTIVE.equalsIgnoreCase(directiveName)) {
					bundleBuilder.addDirective(directiveName, attribEntry.getValue());
				}
			} else {
				bundleBuilder.addAttribute(key, attribEntry.getValue());
			}
		}
		
		caps.add(bundleBuilder.buildCapability());
		if (allowFragments)
			caps.add(hostBuilder.buildCapability());
	}
	
	private void doExports(Resource resource, List<? super Capability> caps) throws Exception {
		Manifest manifest = resource.getManifest();
		
		String exportsStr = manifest.getMainAttributes().getValue(Constants.EXPORT_PACKAGE);
		Map<String, Map<String, String>> exports = OSGiHeader.parseHeader(exportsStr);
		for (Entry<String, Map<String, String>> entry : exports.entrySet()) {
			Builder builder = new Builder().setNamespace(Namespaces.NS_WIRING_PACKAGE);
			
			String pkgName = OSGiHeader.removeDuplicateMarker(entry.getKey());
			builder.addAttribute(Namespaces.NS_WIRING_PACKAGE, pkgName);
			
			String versionStr = entry.getValue().get(Constants.VERSION_ATTRIBUTE);
			Version version = (versionStr != null) ? new Version(versionStr) : new Version(0, 0, 0);
			builder.addAttribute(Namespaces.ATTR_VERSION, version);

			for (Entry<String, String> attribEntry : entry.getValue().entrySet()) {
				String key = attribEntry.getKey();
				if (!"specification-version".equalsIgnoreCase(key) && !Constants.VERSION_ATTRIBUTE.equalsIgnoreCase(key)) {
					if (key.endsWith(":"))
						builder.addDirective(key.substring(0, key.length() - 1), attribEntry.getValue());
					else
						builder.addAttribute(key, attribEntry.getValue());
				}
			}
			
			SymbolicName bsn = Util.getSymbolicName(resource);
			builder.addAttribute(Namespaces.ATTR_BUNDLE_SYMBOLIC_NAME, bsn.getName());
			Version bundleVersion = Util.getVersion(resource);
			builder.addAttribute(Namespaces.ATTR_BUNDLE_VERSION, bundleVersion);
			
			caps.add(builder.buildCapability());
		}
	}

	private void doImports(Resource resource, List<? super Requirement> reqs) throws Exception {
		Manifest manifest = resource.getManifest();
		
		String importsStr = manifest.getMainAttributes().getValue(Constants.IMPORT_PACKAGE);
		Map<String, Map<String, String>> imports = OSGiHeader.parseHeader(importsStr);
		for (Entry<String, Map<String, String>> entry: imports.entrySet()) {
			StringBuilder filter = new StringBuilder();

			String pkgName = OSGiHeader.removeDuplicateMarker(entry.getKey());
			filter.append("(osgi.wiring.package=").append(pkgName).append(")");
			
			String versionStr = entry.getValue().get(Constants.VERSION_ATTRIBUTE);
			if (versionStr != null) {
				VersionRange version = new VersionRange(versionStr);
				filter.insert(0, "(&");
				Util.addVersionFilter(filter, version, VersionKey.PackageVersion);
				filter.append(")");
			}
			
			Builder builder = new Builder()
				.setNamespace(Namespaces.NS_WIRING_PACKAGE)
				.addDirective(Namespaces.DIRECTIVE_FILTER, filter.toString());
			
			copyAttribsAndDirectives(entry.getValue(), builder, Constants.VERSION_ATTRIBUTE, "specification-version");

			reqs.add(builder.buildRequirement());
		}
	}
	
	private void copyAttribsAndDirectives(Map<String, String> input, Builder output, String... ignores) {
		Set<String> ignoreSet = new HashSet<String>(Arrays.asList(ignores));
		
		for (Entry<String, String> entry : input.entrySet()) {
			String key = entry.getKey();
			if (!ignoreSet.contains(key)) {
				if (key.endsWith(":")) {
					String directive = key.substring(0, key.length() - 1);
					output.addDirective(directive, entry.getValue());
				} else {
					output.addAttribute(key, entry.getValue());
				}
			}
		}
	}
	
	private void doRequireBundles(Resource resource, List<? super Requirement> reqs) throws Exception {
		Manifest manifest = resource.getManifest();
		
		String requiresStr = manifest.getMainAttributes().getValue(Constants.REQUIRE_BUNDLE);
		if (requiresStr == null)
			return;
		
		Map<String, Map<String, String>> requires = OSGiHeader.parseHeader(requiresStr);
		for (Entry<String, Map<String, String>> entry : requires.entrySet()) {
			StringBuilder filter = new StringBuilder();
			
			String bsn = OSGiHeader.removeDuplicateMarker(entry.getKey());
			filter.append("(osgi.wiring.bundle=").append(bsn).append(")");
			
			String versionStr = entry.getValue().get(Constants.BUNDLE_VERSION_ATTRIBUTE);
			if (versionStr != null) {
				VersionRange version = new VersionRange(versionStr);
				filter.insert(0, "(&");
				Util.addVersionFilter(filter, version, VersionKey.BundleVersion);
				filter.append(")");
			}
			
			Builder builder = new Builder()
				.setNamespace(Namespaces.NS_WIRING_BUNDLE)
				.addDirective(Namespaces.DIRECTIVE_FILTER, filter.toString());
			
			copyAttribsAndDirectives(entry.getValue(), builder, Constants.BUNDLE_VERSION_ATTRIBUTE);
			
			reqs.add(builder.buildRequirement());
		}
	}
	
	private void doFragment(Resource resource, List<? super Requirement> reqs) throws Exception {
		Manifest manifest = resource.getManifest();
		String fragmentHost = manifest.getMainAttributes().getValue(Constants.FRAGMENT_HOST);
		
		if (fragmentHost != null) {
			StringBuilder filter = new StringBuilder();
			Map<String, Map<String, String>> fragmentList = OSGiHeader.parseHeader(fragmentHost);
			if (fragmentList.size() != 1)
				throw new IllegalArgumentException("Invalid Fragment-Host header: cannot contain multiple entries");
			Entry<String, Map<String, String>> entry = fragmentList.entrySet().iterator().next();
			
			String bsn = entry.getKey();
			filter.append("(&(osgi.wiring.host=").append(bsn).append(")");
			
			String versionStr = entry.getValue().get(Constants.BUNDLE_VERSION_ATTRIBUTE);
			VersionRange version = versionStr != null ? new VersionRange(versionStr) : new VersionRange(Version.emptyVersion.toString());
			Util.addVersionFilter(filter, version, VersionKey.BundleVersion);
			filter.append(")");
			
			Builder builder = new Builder()
				.setNamespace(Namespaces.NS_WIRING_HOST)
				.addDirective(Namespaces.DIRECTIVE_FILTER, filter.toString());
			
			reqs.add(builder.buildRequirement());
		}
	}
	
	private void doExportService(Resource resource, List<? super Capability> caps) throws Exception {
		@SuppressWarnings("deprecation")
		String exportsStr = resource.getManifest().getMainAttributes().getValue(Constants.EXPORT_SERVICE);
		Map<String, Map<String, String>> exports = OSGiHeader.parseHeader(exportsStr);
		
		for (Entry<String, Map<String, String>> export : exports.entrySet()) {
			String service = OSGiHeader.removeDuplicateMarker(export.getKey());
			Builder builder = new Builder()
					.setNamespace(Namespaces.NS_SERVICE)
					.addAttribute(Constants.OBJECTCLASS, service);
			for (Entry<String,String> attribEntry : export.getValue().entrySet())
				builder.addAttribute(attribEntry.getKey(), attribEntry.getValue());
			builder.addDirective(Namespaces.DIRECTIVE_EFFECTIVE, Namespaces.EFFECTIVE_ACTIVE);
			caps.add(builder.buildCapability());
		}
	}
	
	private void doImportService(Resource resource, List<? super Requirement> reqs) throws Exception {
		@SuppressWarnings("deprecation")
		String importsStr = resource.getManifest().getMainAttributes().getValue(Constants.IMPORT_SERVICE);
		Map<String, Map<String, String>> imports = OSGiHeader.parseHeader(importsStr);
		
		for (Entry<String, Map<String, String>> imp : imports.entrySet()) {
			String service = OSGiHeader.removeDuplicateMarker(imp.getKey());
			StringBuilder filter = new StringBuilder();
			filter.append('(').append(Constants.OBJECTCLASS).append('=').append(service).append(')');
			
			Builder builder = new Builder()
				.setNamespace(Namespaces.NS_SERVICE)
				.addDirective(Namespaces.DIRECTIVE_FILTER, filter.toString())
				.addDirective(Namespaces.DIRECTIVE_EFFECTIVE, Namespaces.EFFECTIVE_ACTIVE);
			reqs.add(builder.buildRequirement());
		}
	}
	
	private void doBREE(Resource resource, List<? super Requirement> reqs) throws Exception {
		String breeStr = resource.getManifest().getMainAttributes().getValue(Constants.BUNDLE_REQUIREDEXECUTIONENVIRONMENT);
		Map<String, Map<String, String>> brees = OSGiHeader.parseHeader(breeStr);
		
		final String filter;
		if (!brees.isEmpty()) {
			if (brees.size() == 1) {
				String bree = brees.keySet().iterator().next();
				filter = String.format("(&(%s=%s)(version=%s))", Namespaces.NS_EE, getBREEName(bree), getBREEVersion(bree));
			} else {
				StringBuilder builder = new StringBuilder().append("(|");
				for (String bree : brees.keySet()) {
					bree = OSGiHeader.removeDuplicateMarker(bree);
					builder.append(String.format("(&(%s=%s)(version=%s))", Namespaces.NS_EE, getBREEName(bree), getBREEVersion(bree)));
				}
				builder.append(')');
				filter = builder.toString();
			}
			
			Requirement requirement = new Builder()
			.setNamespace(Namespaces.NS_EE)
			.addDirective(Namespaces.DIRECTIVE_FILTER, filter)
			.buildRequirement();
			reqs.add(requirement);
		}
	}
	
	private void doCapabilities(Resource resource, final List<? super Capability> caps) throws Exception {
		String capsStr = resource.getManifest().getMainAttributes().getValue(PROVIDE_CAPABILITY);
		buildFromHeader(capsStr, new Yield<Builder>() {
			public void yield(Builder builder) {
				caps.add(builder.buildCapability());
			}
		});
	}
	
	private void doRequirements(Resource resource, final List<? super Requirement> reqs) throws IOException {
		String reqsStr = resource.getManifest().getMainAttributes().getValue(REQUIRE_CAPABILITY);
		buildFromHeader(reqsStr, new Yield<Builder>() {
			public void yield(Builder builder) {
				reqs.add(builder.buildRequirement());
			}
		});
	}
	
	private static void buildFromHeader(String headerStr, Yield<Builder> output) {
		if (headerStr == null) return;
		Map<String, Map<String, String>> header = OSGiHeader.parseHeader(headerStr);
		
		for (Entry<String, Map<String, String>> entry : header.entrySet()) {
			String namespace = OSGiHeader.removeDuplicateMarker(entry.getKey());
			Builder builder = new Builder().setNamespace(namespace);
			
			Map<String, String> attribs = entry.getValue();
			Util.copyAttribsToBuilder(builder, attribs);
			output.yield(builder);
		}
	}

}

