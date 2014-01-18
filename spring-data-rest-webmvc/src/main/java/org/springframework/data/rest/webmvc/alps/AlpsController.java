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

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.repository.support.Repositories;
import org.springframework.data.rest.core.mapping.ResourceMappings;
import org.springframework.data.rest.core.mapping.ResourceMetadata;
import org.springframework.data.rest.webmvc.RootResourceInformation;
import org.springframework.hateoas.alps.Alps;
import org.springframework.hateoas.alps.Descriptor;
import org.springframework.hateoas.alps.Descriptor.DescriptorBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Controller exposing semantic documentation for the resources exposed using the Application Level Profile Semantics
 * format.
 * 
 * @author Oliver Gierke
 * @see http://alps.io
 */
@Controller
@RequestMapping("/alps")
public class AlpsController {

	private final Repositories repositories;
	private final RootResourceInformationToAlpsDescriptorConverter converter;
	private final ResourceMappings mappings;

	/**
	 * Creates a new {@link AlpsController} for the given {@link Repositories},
	 * {@link RootResourceInformationToAlpsDescriptorConverter} and {@link ResourceMappings}.
	 * 
	 * @param repositories must not be {@literal null}.
	 * @param converter must not be {@literal null}.
	 * @param mappings must not be {@literal null}.
	 */
	public AlpsController(Repositories repositories, RootResourceInformationToAlpsDescriptorConverter converter,
			ResourceMappings mappings) {

		Assert.notNull(repositories, "Repositories must not be null!");
		Assert.notNull(converter, "RootResourceInformationToAlpsDescriptorConverter must not be null!");
		Assert.notNull(mappings, "ResourceMappings must not be null!");

		this.repositories = repositories;
		this.converter = converter;
		this.mappings = mappings;
	}

	/**
	 * Exposes a resource to contain descriptors pointing to the discriptors for individual resources.
	 * 
	 * @return
	 */
	@RequestMapping(method = RequestMethod.GET)
	HttpEntity<Alps> alps() {

		List<Descriptor> descriptors = new ArrayList<Descriptor>();

		for (Class<?> domainType : repositories) {

			UriComponentsBuilder builder = ServletUriComponentsBuilder.fromCurrentRequestUri();
			ResourceMetadata mapping = mappings.getMappingFor(domainType);
			String href = builder.pathSegment(mapping.getRel()).build().toUriString();

			DescriptorBuilder descriptorBuilder = converter.getSafeDescriptorBuilder(mapping.getRel(),
					mapping.getItemResourceDescription());
			descriptors.add(descriptorBuilder.href(href).build());
		}

		Alps alps = Alps.alps().//
				descriptors(descriptors).//
				build();

		return new ResponseEntity<Alps>(alps, HttpStatus.OK);
	}

	/**
	 * Exposes an ALPS resource to describe an individual repository resource.
	 * 
	 * @param information
	 * @return
	 */
	@RequestMapping("/{repository}")
	HttpEntity<RootResourceInformation> descriptor(RootResourceInformation information) {
		return new ResponseEntity<RootResourceInformation>(information, HttpStatus.OK);
	}
}
