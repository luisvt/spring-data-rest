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

import static org.springframework.hateoas.alps.Alps.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.springframework.context.support.MessageSourceAccessor;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.mapping.Association;
import org.springframework.data.mapping.PersistentEntity;
import org.springframework.data.mapping.PersistentProperty;
import org.springframework.data.mapping.SimpleAssociationHandler;
import org.springframework.data.mapping.SimplePropertyHandler;
import org.springframework.data.mapping.context.PersistentEntities;
import org.springframework.data.repository.core.RepositoryInformation;
import org.springframework.data.repository.support.Repositories;
import org.springframework.data.rest.core.config.ProjectionDefinitionConfiguration;
import org.springframework.data.rest.core.config.RepositoryRestConfiguration;
import org.springframework.data.rest.core.mapping.AnnotationBasedResourceDescription;
import org.springframework.data.rest.core.mapping.MethodResourceMapping;
import org.springframework.data.rest.core.mapping.ResourceDescription;
import org.springframework.data.rest.core.mapping.ResourceMapping;
import org.springframework.data.rest.core.mapping.ResourceMappings;
import org.springframework.data.rest.core.mapping.ResourceMetadata;
import org.springframework.data.rest.core.mapping.SimpleResourceDescription;
import org.springframework.data.rest.webmvc.ResourceType;
import org.springframework.data.rest.webmvc.RootResourceInformation;
import org.springframework.hateoas.EntityLinks;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.TemplateVariable;
import org.springframework.hateoas.alps.Alps;
import org.springframework.hateoas.alps.Descriptor;
import org.springframework.hateoas.alps.Descriptor.DescriptorBuilder;
import org.springframework.hateoas.alps.Doc;
import org.springframework.hateoas.alps.Format;
import org.springframework.hateoas.alps.Type;
import org.springframework.hateoas.mvc.ControllerLinkBuilder;

/**
 * @author Oliver Gierke
 */
public class RootResourceInformationToAlpsDescriptorConverter implements Converter<RootResourceInformation, Alps> {

	private final Repositories repositories;
	private final PersistentEntities persistentEntities;
	private final ResourceMappings mappings;
	private final EntityLinks entityLinks;
	private final MessageSourceAccessor messageSource;
	private final RepositoryRestConfiguration configuration;

	/**
	 * @param mappings
	 */
	public RootResourceInformationToAlpsDescriptorConverter(ResourceMappings mappings, Repositories repositories,
			PersistentEntities entities, EntityLinks entityLinks, MessageSourceAccessor messageSource,
			RepositoryRestConfiguration configuration) {

		this.mappings = mappings;
		this.persistentEntities = entities;
		this.repositories = repositories;
		this.entityLinks = entityLinks;
		this.messageSource = messageSource;
		this.configuration = configuration;
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.core.convert.converter.Converter#convert(java.lang.Object)
	 */
	@Override
	public Alps convert(RootResourceInformation resourceInformation) {

		Class<?> type = resourceInformation.getDomainType();
		Descriptor itemResourceDescriptor = buildItemResourceDescriptor(resourceInformation);
		List<Descriptor> descriptors = new ArrayList<Descriptor>();

		if (!resourceInformation.getSupportedMethods(ResourceType.COLLECTION).isEmpty()) {
			descriptors.add(buildCollectionResourceDescriptor(type, resourceInformation, itemResourceDescriptor));
		}

		if (!resourceInformation.getSupportedMethods(ResourceType.ITEM).isEmpty()) {
			descriptors.add(itemResourceDescriptor);
		}

		descriptors.addAll(buildSearchResourceDescriptors(resourceInformation.getPersistentEntity()));

		return Alps.alps().descriptors(descriptors).build();
	}

	private Descriptor buildCollectionResourceDescriptor(Class<?> type, RootResourceInformation resourceInformation,
			Descriptor itemResourceDescriptor) {

		ResourceMetadata metadata = mappings.getMappingFor(type);
		RepositoryInformation information = repositories.getRepositoryInformationFor(type);

		// Add collection rel
		DescriptorBuilder descriptorBuilder = getSafeDescriptorBuilder(metadata.getRel(), metadata.getDescription());
		descriptorBuilder.rt("#" + itemResourceDescriptor.getName());

		List<Descriptor> nestedDescriptors = new ArrayList<Descriptor>();

		if (information.isPagingRepository()) {

			Link linkToCollectionResource = entityLinks.linkToCollectionResource(type);

			for (TemplateVariable variable : linkToCollectionResource.getVariables()) {

				ResourceDescription description = SimpleResourceDescription.defaultFor(variable.getDescription());
				nestedDescriptors.add(getSemanticDescriptorBuilder(variable.getName(), description).build());
			}
		}

		ProjectionDefinitionConfiguration projectionConfiguration = configuration.projectionConfiguration();

		if (projectionConfiguration.hasProjectionFor(type)) {
			nestedDescriptors.add(buildProjectionDescriptor(metadata, projectionConfiguration));
		}

		return descriptorBuilder.descriptors(nestedDescriptors).build();
	}

	private Descriptor buildProjectionDescriptor(ResourceMetadata metadata,
			ProjectionDefinitionConfiguration projectionConfiguration) {

		String projectionParameterName = projectionConfiguration.getParameterName();
		DescriptorBuilder projectionBuilder = getSemanticDescriptorBuilder(projectionParameterName,
				SimpleResourceDescription.defaultFor(projectionParameterName));

		Map<String, Class<?>> projections = projectionConfiguration.getProjectionsFor(metadata.getDomainType());
		List<Descriptor> projectionDescriptors = new ArrayList<Descriptor>(projections.size());

		for (Entry<String, Class<?>> projection : projections.entrySet()) {

			String key = String.format("%s.%s.%s", metadata.getRel(), projectionParameterName, projection.getKey());
			ResourceDescription fallback = SimpleResourceDescription.defaultFor(key);
			AnnotationBasedResourceDescription projectionDescription = new AnnotationBasedResourceDescription(
					projection.getValue(), fallback);

			projectionDescriptors.add(getSemanticDescriptorBuilder(projection.getKey(), projectionDescription).build());
		}

		return projectionBuilder.descriptors(projectionDescriptors).build();
	}

	private Descriptor buildItemResourceDescriptor(RootResourceInformation resourceInformation) {

		PersistentEntity<?, ?> entity = resourceInformation.getPersistentEntity();

		ResourceMetadata metadata = mappings.getMappingFor(entity.getType());
		List<Descriptor> propertyDescriptors = buildPropertyDescriptors(entity, metadata.getItemResourceRel());

		if (resourceInformation.getSupportedMethods(ResourceType.ITEM).isEmpty()) {
			return getSemanticDescriptorBuilder(metadata.getItemResourceRel(), metadata.getItemResourceDescription())
					.descriptors(propertyDescriptors).build();
		} else {
			return getSafeDescriptorBuilder(metadata.getItemResourceRel(), metadata.getItemResourceDescription()).//
					descriptors(propertyDescriptors).//
					build();
		}
	}

	private List<Descriptor> buildPropertyDescriptors(PersistentEntity<?, ?> entity, final String baseRel) {

		final ResourceMetadata metadata = mappings.getMappingFor(entity.getType());
		final List<Descriptor> propertyDescriptors = new ArrayList<Descriptor>();

		entity.doWithProperties(new SimplePropertyHandler() {

			@Override
			public void doWithPersistentProperty(PersistentProperty<?> property) {

				ResourceDescription description = metadata == null ? SimpleResourceDescription.defaultFor(property, baseRel)
						: metadata.getMappingFor(property).getDescription();
				DescriptorBuilder builder = getSemanticDescriptorBuilder(property.getName(), description);

				propertyDescriptors.add(builder.build());
			}
		});

		entity.doWithAssociations(new SimpleAssociationHandler() {

			@Override
			public void doWithAssociation(Association<? extends PersistentProperty<?>> association) {

				PersistentProperty<?> property = association.getInverse();
				ResourceMapping mapping = metadata.getMappingFor(property);
				DescriptorBuilder builder = null;

				if (metadata.isManagedResource(property)) {

					builder = getSafeDescriptorBuilder(mapping.getRel(), mapping.getDescription());
					ResourceMetadata targetTypeMapping = mappings.getMappingFor(property.getActualType());

					Link link = ControllerLinkBuilder.linkTo(AlpsController.class)
							.slash(targetTypeMapping.getRel().concat("#").concat(targetTypeMapping.getItemResourceRel()))
							.withSelfRel();
					builder.rt(link.getHref());

				} else {
					;

					builder = getSemanticDescriptorBuilder(mapping.getRel(), mapping.getDescription());
					PersistentEntity<?, ?> nestedEntity = persistentEntities.getPersistentEntity(property.getActualType());
					builder.descriptors(buildPropertyDescriptors(nestedEntity, baseRel.concat(".").concat(mapping.getRel())));
				}

				propertyDescriptors.add(builder.build());
			}
		});

		return propertyDescriptors;
	}

	private Collection<Descriptor> buildSearchResourceDescriptors(PersistentEntity<?, ?> entity) {

		ResourceMetadata metadata = mappings.getMappingFor(entity.getType());
		List<Descriptor> descriptors = new ArrayList<Descriptor>();

		for (MethodResourceMapping methodMapping : metadata.getSearchResourceMappings()) {

			List<Descriptor> parameterDescriptors = new ArrayList<Descriptor>();

			for (String parameterName : methodMapping.getParameterNames()) {
				parameterDescriptors.add(descriptor().name(parameterName).type(Type.SEMANTIC).build());
			}

			descriptors.add(descriptor().//
					type(Type.SAFE).//
					name(methodMapping.getRel()).//
					descriptors(parameterDescriptors).//
					build());
		}

		return descriptors;
	}

	public DescriptorBuilder getSafeDescriptorBuilder(String name, ResourceDescription description) {

		return descriptor().//
				name(name).//
				type(Type.SAFE).//
				doc(getDocFor(description));
	}

	private DescriptorBuilder getSemanticDescriptorBuilder(String name, ResourceDescription description) {

		return descriptor().//
				name(name).//
				type(Type.SEMANTIC).//
				doc(getDocFor(description));
	}

	private Doc getDocFor(ResourceDescription description) {
		return new Doc(messageSource.getMessage(description), Format.TEXT);
	}
}
