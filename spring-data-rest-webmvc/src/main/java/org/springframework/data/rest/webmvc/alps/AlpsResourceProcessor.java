/*
 * Copyright 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.rest.webmvc.alps;

import static org.springframework.hateoas.core.DummyInvocationUtils.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.rest.webmvc.RepositoryLinksResource;
import org.springframework.hateoas.MethodLinkBuilderFactory;
import org.springframework.hateoas.ResourceProcessor;

/**
 * @author Oliver Gierke
 */
public class AlpsResourceProcessor implements ResourceProcessor<RepositoryLinksResource> {

	private final MethodLinkBuilderFactory<?> factory;

	@Autowired
	public AlpsResourceProcessor(MethodLinkBuilderFactory<?> factory) {
		this.factory = factory;
	}

	/* 
	 * (non-Javadoc)
	 * @see org.springframework.hateoas.ResourceProcessor#process(org.springframework.hateoas.ResourceSupport)
	 */
	@Override
	public RepositoryLinksResource process(RepositoryLinksResource resource) {

		resource.add(factory.linkTo(methodOn(AlpsController.class).alps()).withRel("profile"));
		return resource;
	}
}
