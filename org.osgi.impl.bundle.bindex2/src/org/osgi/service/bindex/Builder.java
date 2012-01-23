package org.osgi.service.bindex;

import java.util.HashMap;
import java.util.Map;

public class Builder {

	private String namespace = null;
	private final Map<String, Object> attributes = new HashMap<String, Object>();
	private final Map<String, String> directives = new HashMap<String, String>();

	public Builder setNamespace(String namespace) {
		this.namespace = namespace;
		return this;
	}

	public Builder addAttribute(String name, Object value) {
		attributes.put(name, value);
		return this;
	}

	public Builder addDirective(String name, String value) {
		directives.put(name, value);
		return this;
	}

	public Capability buildCapability() throws IllegalStateException {
		if (namespace == null)
			throw new IllegalStateException("Namespace not set");

		return new Capability(namespace, new HashMap<String, Object>(attributes), new HashMap<String, String>(directives));
	}

	public Requirement buildRequirement() throws IllegalStateException {
		if (namespace == null)
			throw new IllegalStateException("Namespace not set");

		return new Requirement(namespace, new HashMap<String, Object>(attributes), new HashMap<String, String>(directives));
	}

}
