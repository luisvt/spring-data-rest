/*
 * Copyright 2013-2014 the original author or authors.
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
package org.springframework.data.rest.webmvc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ser.FilterProvider;
import com.fasterxml.jackson.databind.ser.impl.SimpleBeanPropertyFilter;
import com.fasterxml.jackson.databind.ser.impl.SimpleFilterProvider;
import java.io.IOException;
import java.io.OutputStream;
import static org.springframework.data.rest.core.support.DomainObjectMerger.NullHandlingPolicy.*;
import static org.springframework.http.HttpMethod.*;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.core.convert.ConversionService;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mapping.model.BeanWrapper;
import org.springframework.data.repository.core.RepositoryMetadata;
import org.springframework.data.repository.support.Repositories;
import org.springframework.data.rest.core.config.RepositoryRestConfiguration;
import org.springframework.data.rest.core.event.AfterCreateEvent;
import org.springframework.data.rest.core.event.AfterDeleteEvent;
import org.springframework.data.rest.core.event.AfterSaveEvent;
import org.springframework.data.rest.core.event.BeforeCreateEvent;
import org.springframework.data.rest.core.event.BeforeDeleteEvent;
import org.springframework.data.rest.core.event.BeforeSaveEvent;
import org.springframework.data.rest.core.invoke.RepositoryInvoker;
import org.springframework.data.rest.core.mapping.ResourceMetadata;
import org.springframework.data.rest.core.mapping.SearchResourceMappings;
import org.springframework.data.rest.core.support.DomainObjectMerger;
import org.springframework.data.rest.core.support.DomainObjectMerger.NullHandlingPolicy;
import org.springframework.data.rest.webmvc.jsonfilterannotations.SerializeOnePropertiesFilters;
import org.springframework.data.rest.webmvc.support.BackendId;
import org.springframework.data.web.PagedResourcesAssembler;
import org.springframework.hateoas.EntityLinks;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.PagedResources;
import org.springframework.hateoas.Resource;
import org.springframework.hateoas.ResourceSupport;
import org.springframework.hateoas.Resources;
import org.springframework.hateoas.UriTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * @author Jon Brisbin
 * @author Oliver Gierke
 * @author Greg Turnquist
 */
@RepositoryRestController
public class RepositoryEntityController extends JsonRepositoryEntityController implements ApplicationEventPublisherAware {

    protected static final String BASE_MAPPING = "/{repository}";

    private final EntityLinks entityLinks;
    private final RepositoryRestConfiguration config;
    private final ConversionService conversionService;
    private final DomainObjectMerger domainObjectMerger;

    private ApplicationEventPublisher publisher;

    @Autowired
    public RepositoryEntityController(Repositories repositories, RepositoryRestConfiguration config,
            EntityLinks entityLinks, PagedResourcesAssembler<Object> assembler,
            @Qualifier("defaultConversionService") ConversionService conversionService, DomainObjectMerger domainObjectMerger) {

        super(assembler);

        this.entityLinks = entityLinks;
        this.config = config;
        this.conversionService = conversionService;
        this.domainObjectMerger = domainObjectMerger;
    }

    /*
     * (non-Javadoc)
     * @see org.springframework.context.ApplicationEventPublisherAware#setApplicationEventPublisher(org.springframework.context.ApplicationEventPublisher)
     */
    @Override
    public void setApplicationEventPublisher(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @ResponseBody
    @RequestMapping(value = BASE_MAPPING, method = RequestMethod.GET)
    public Resources<?> getCollectionResource(final RootResourceInformation resourceInformation, Pageable pageable,
            Sort sort, PersistentEntityResourceAssembler assembler) throws ResourceNotFoundException,
            HttpRequestMethodNotSupportedException {

        resourceInformation.verifySupportedMethod(HttpMethod.GET, ResourceType.COLLECTION);

        RepositoryInvoker invoker = resourceInformation.getInvoker();

        if (null == invoker) {
            throw new ResourceNotFoundException();
        }

        Iterable<?> results;

        if (pageable != null) {
            results = invoker.invokeFindAll(pageable);
        } else {
            results = invoker.invokeFindAll(sort);
        }

        ResourceMetadata metadata = resourceInformation.getResourceMetadata();
        SearchResourceMappings searchMappings = metadata.getSearchResourceMappings();
        List<Link> links = new ArrayList<Link>();

        if (searchMappings.isExported()) {
            links.add(entityLinks.linkFor(metadata.getDomainType()).slash(searchMappings.getPath())
                    .withRel(searchMappings.getRel()));
        }

        Resources<?> resources = resultToResources(results, assembler);
        resources.add(links);
        return resources;
    }

    @ResponseBody
    @SuppressWarnings({"unchecked"})
    @RequestMapping(value = BASE_MAPPING, method = RequestMethod.GET, produces = {
        "application/x-spring-data-compact+json", "text/uri-list"})
    public Resources<?> getCollectionResourceCompact(RootResourceInformation repoRequest, Pageable pageable, Sort sort,
            PersistentEntityResourceAssembler assembler) throws ResourceNotFoundException,
            HttpRequestMethodNotSupportedException {

        Resources<?> resources = getCollectionResource(repoRequest, pageable, sort, assembler);
        List<Link> links = new ArrayList<Link>(resources.getLinks());

        for (Resource<?> resource : ((Resources<Resource<?>>) resources).getContent()) {
            PersistentEntityResource<?> persistentEntityResource = (PersistentEntityResource<?>) resource;
            links.add(resourceLink(repoRequest, persistentEntityResource));
        }
        if (resources instanceof PagedResources) {
            return new PagedResources<Object>(Collections.emptyList(), ((PagedResources<?>) resources).getMetadata(), links);
        } else {
            return new Resources<Object>(Collections.emptyList(), links);
        }
    }

    /**
     * <code>POST /{repository}</code> - Creates a new entity instances from the
     * collection resource.
     *
     * @param resourceInformation
     * @param payload
     * @return
     * @throws HttpRequestMethodNotSupportedException
     */
    @ResponseBody
    @RequestMapping(value = BASE_MAPPING, method = RequestMethod.POST)
    public ResponseEntity<ResourceSupport> postCollectionResource(RootResourceInformation resourceInformation,
            PersistentEntityResource<?> payload, PersistentEntityResourceAssembler assembler)
            throws HttpRequestMethodNotSupportedException {

        resourceInformation.verifySupportedMethod(HttpMethod.POST, ResourceType.COLLECTION);

        return createAndReturn(payload.getContent(), resourceInformation.getInvoker(), assembler);
    }

    /**
     * <code>GET /{repository}/{id}</code> - Returns a single entity.
     *
     * @param resourceInformation
     * @param id
     * @return
     * @throws HttpRequestMethodNotSupportedException
     */
    @RequestMapping(value = BASE_MAPPING + "/{id}", method = RequestMethod.GET, produces = "application/hal+json")
    public ResponseEntity<Resource<?>> getItemResource(RootResourceInformation resourceInformation,
            @BackendId Serializable id, PersistentEntityResourceAssembler assembler)
            throws HttpRequestMethodNotSupportedException {

        resourceInformation.verifySupportedMethod(HttpMethod.GET, ResourceType.ITEM);

        RepositoryInvoker repoMethodInvoker = resourceInformation.getInvoker();

        if (!repoMethodInvoker.exposesFindOne()) {
            return new ResponseEntity<Resource<?>>(HttpStatus.NOT_FOUND);
        }

        Object domainObj = repoMethodInvoker.invokeFindOne(id);

        if (domainObj == null) {
            return new ResponseEntity<Resource<?>>(HttpStatus.NOT_FOUND);
        }

        return new ResponseEntity<Resource<?>>(assembler.toResource(domainObj), HttpStatus.OK);
    }

    /**
     * <code>PUT /{repository}/{id}</code> - Updates an existing entity or
     * creates one at exactly that place.
     *
     * @param resourceInformation
     * @param payload
     * @param id
     * @return
     * @throws HttpRequestMethodNotSupportedException
     */
    @RequestMapping(value = BASE_MAPPING + "/{id}", method = RequestMethod.PUT)
    public ResponseEntity<? extends ResourceSupport> putItemResource(RootResourceInformation resourceInformation,
            PersistentEntityResource<Object> payload, @BackendId Serializable id, PersistentEntityResourceAssembler assembler)
            throws HttpRequestMethodNotSupportedException {

        resourceInformation.verifySupportedMethod(HttpMethod.PUT, ResourceType.ITEM);

        Object domainObject = conversionService.convert(id, resourceInformation.getDomainType());
        RepositoryInvoker invoker = resourceInformation.getInvoker();

        if (domainObject == null) {

            BeanWrapper<Object> incomingWrapper = BeanWrapper.create(payload.getContent(), conversionService);
            incomingWrapper.setProperty(payload.getPersistentEntity().getIdProperty(), id);

            return createAndReturn(incomingWrapper.getBean(), invoker, assembler);
        }

        return mergeAndReturn(payload.getContent(), domainObject, invoker, PUT, assembler);
    }

    /**
     * <code>PUT /{repository}/{id}</code> - Updates an existing entity or
     * creates one at exactly that place.
     *
     * @param resourceInformation
     * @param payload
     * @param id
     * @return
     * @throws HttpRequestMethodNotSupportedException
     * @throws ResourceNotFoundException
     */
    @RequestMapping(value = BASE_MAPPING + "/{id}", method = RequestMethod.PATCH)
    public ResponseEntity<ResourceSupport> patchItemResource(RootResourceInformation resourceInformation,
            PersistentEntityResource<Object> payload, @BackendId Serializable id, PersistentEntityResourceAssembler assembler)
            throws HttpRequestMethodNotSupportedException, ResourceNotFoundException {

        resourceInformation.verifySupportedMethod(HttpMethod.PATCH, ResourceType.ITEM);

        Object domainObject = conversionService.convert(id, resourceInformation.getDomainType());

        if (domainObject == null) {
            throw new ResourceNotFoundException();
        }

        return mergeAndReturn(payload.getContent(), domainObject, resourceInformation.getInvoker(), PATCH, assembler);
    }

    /**
     * <code>DELETE /{repository}/{id}</code> - Deletes the entity backing the
     * item resource.
     *
     * @param resourceInformation
     * @param id
     * @return
     * @throws ResourceNotFoundException
     * @throws HttpRequestMethodNotSupportedException
     */
    @RequestMapping(value = BASE_MAPPING + "/{id}", method = RequestMethod.DELETE)
    public ResponseEntity<?> deleteItemResource(RootResourceInformation resourceInformation, @BackendId Serializable id)
            throws ResourceNotFoundException, HttpRequestMethodNotSupportedException {

        resourceInformation.verifySupportedMethod(HttpMethod.DELETE, ResourceType.ITEM);

        RepositoryInvoker invoker = resourceInformation.getInvoker();

        // TODO: re-enable not exposing delete method if hidden
        // ResourceMapping methodMapping = repoRequest.getRepositoryResourceMapping().getResourceMappingFor("delete");
        // if (null != methodMapping && !methodMapping.isExported()) {
        // throw new HttpRequestMethodNotSupportedException("DELETE");
        // }
        Object domainObj = invoker.invokeFindOne(id);

        publisher.publishEvent(new BeforeDeleteEvent(domainObj));
        invoker.invokeDelete(id);
        publisher.publishEvent(new AfterDeleteEvent(domainObj));

        return new ResponseEntity<Object>(HttpStatus.NO_CONTENT);
    }

    /**
     * Merges the given incoming object into the given domain object.
     *
     * @param incoming
     * @param domainObject
     * @param invoker
     * @param httpMethod
     * @return
     */
    private ResponseEntity<ResourceSupport> mergeAndReturn(Object incoming, Object domainObject,
            RepositoryInvoker invoker, HttpMethod httpMethod, PersistentEntityResourceAssembler assembler) {

        NullHandlingPolicy nullPolicy = httpMethod.equals(PATCH) ? IGNORE_NULLS : APPLY_NULLS;
        domainObjectMerger.merge(incoming, domainObject, nullPolicy);

        publisher.publishEvent(new BeforeSaveEvent(domainObject));
        Object obj = invoker.invokeSave(domainObject);
        publisher.publishEvent(new AfterSaveEvent(domainObject));

        HttpHeaders headers = new HttpHeaders();

        if (PUT.equals(httpMethod)) {
            addLocationHeader(headers, assembler, obj);
        }

        if (config.isReturnBodyOnUpdate()) {
            return ControllerUtils.toResponseEntity(HttpStatus.OK, headers, assembler.toResource(obj));
        } else {
            return ControllerUtils.toEmptyResponse(HttpStatus.NO_CONTENT, headers);
        }
    }

    /**
     * Triggers the creation of the domain object and renders it into the
     * response if needed.
     *
     * @param domainObject
     * @param invoker
     * @return
     */
    private ResponseEntity<ResourceSupport> createAndReturn(Object domainObject, RepositoryInvoker invoker,
            PersistentEntityResourceAssembler assembler) {

        publisher.publishEvent(new BeforeCreateEvent(domainObject));
        Object savedObject = invoker.invokeSave(domainObject);
        publisher.publishEvent(new AfterCreateEvent(savedObject));

        HttpHeaders headers = new HttpHeaders();
        addLocationHeader(headers, assembler, savedObject);

        PersistentEntityResource<Object> resource = config.isReturnBodyOnCreate() ? assembler.toResource(savedObject)
                : null;
        return ControllerUtils.toResponseEntity(HttpStatus.CREATED, headers, resource);
    }

    /**
     * Sets the location header pointing to the resource representing the given
     * instance. Will make sure we properly expand the URI template potentially
     * created as self link.
     *
     * @param headers must not be {@literal null}.
     * @param assembler must not be {@literal null}.
     * @param source must not be {@literal null}.
     */
    private void addLocationHeader(HttpHeaders headers, PersistentEntityResourceAssembler assembler, Object source) {

        String selfLink = assembler.getSelfLinkFor(source).getHref();
        headers.setLocation(new UriTemplate(selfLink).expand());
    }
}
