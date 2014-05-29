/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.springframework.data.rest.webmvc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.Annotated;
import com.fasterxml.jackson.databind.introspect.AnnotatedClass;
import com.fasterxml.jackson.databind.introspect.JacksonAnnotationIntrospector;
import com.fasterxml.jackson.databind.ser.impl.SimpleBeanPropertyFilter;
import com.fasterxml.jackson.databind.ser.impl.SimpleFilterProvider;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Serializable;
import java.lang.reflect.Field;
import org.springframework.data.repository.core.RepositoryMetadata;
import org.springframework.data.rest.core.invoke.RepositoryInvoker;
import org.springframework.data.rest.core.mapping.ResourceMetadata;
import static org.springframework.data.rest.webmvc.RepositoryEntityController.BASE_MAPPING;
import org.springframework.data.rest.webmvc.jsonfilterannotations.SerializeOnePropertiesFilter;
import org.springframework.data.rest.webmvc.jsonfilterannotations.SerializeOnePropertiesFilters;
import org.springframework.data.rest.webmvc.support.BackendId;
import org.springframework.data.web.PagedResourcesAssembler;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 *
 * @author luis
 */
public class JsonRepositoryEntityController extends AbstractRepositoryRestController {

    public JsonRepositoryEntityController(PagedResourcesAssembler<Object> pagedResourcesAssembler) {
        super(pagedResourcesAssembler);
    }

    public class CustomIntrospector extends JacksonAnnotationIntrospector {

        @Override
        public Object findFilterId(Annotated a) {
            // Let's default to current behavior if annotation is found:
            Object id = super.findFilterId(a);
            // but use simple class name if not
            if (id == null) {
                int beginIndex = a.getName().lastIndexOf('.') + 1;
                id = a.getName().substring(beginIndex);
            }
            return id;
        }
    }

    public void processOutput(
            ResourceMetadata resourceMetadata, Object domainObj, OutputStream outputStream
    ) throws IOException, NoSuchFieldException, IllegalArgumentException, IllegalAccessException {

        Class resourceMetaDataClass = resourceMetadata.getClass();
        Field field = resourceMetaDataClass.getDeclaredField("repositoryInterface");
        field.setAccessible(true);
        RepositoryMetadata repositoryMetadata = (RepositoryMetadata) field.get(resourceMetadata);
        field.setAccessible(false);

        SerializeOnePropertiesFilters ann = repositoryMetadata.getRepositoryInterface().getAnnotation(SerializeOnePropertiesFilters.class);

        SimpleFilterProvider filterProvider = new SimpleFilterProvider().setDefaultFilter(SimpleBeanPropertyFilter.filterOutAllExcept(""));
        if (ann != null) {
            for (SerializeOnePropertiesFilter serializeOnePropertiesFilter : ann.value()) {
                String className = serializeOnePropertiesFilter.className();
                String[] exclude = serializeOnePropertiesFilter.exclude();
                String[] include = serializeOnePropertiesFilter.include();
                if (exclude.length > 0) {
                    filterProvider.addFilter(className,
                            SimpleBeanPropertyFilter.serializeAllExcept(exclude));
                } else if (include.length > 0) {
                    filterProvider.addFilter(className,
                            SimpleBeanPropertyFilter.filterOutAllExcept(include));
                } else {
                    filterProvider.addFilter(className,
                            SimpleBeanPropertyFilter.serializeAllExcept(""));
                }
            }
        }

        new ObjectMapper()
                .setAnnotationIntrospector(new CustomIntrospector())
                .writerWithDefaultPrettyPrinter()
                .with(filterProvider)
                .writeValue(outputStream, domainObj);
    }

    /**
     * <code>GET /{repository}/{id}</code> - Returns a single entity.
     *
     * @param resourceInformation
     * @param id
     * @param output
     * @return
     * @throws HttpRequestMethodNotSupportedException
     * @throws com.fasterxml.jackson.core.JsonProcessingException
     * @throws java.lang.NoSuchFieldException
     * @throws java.lang.IllegalAccessException
     */
    @RequestMapping(value = BASE_MAPPING + "/{id}", method = RequestMethod.GET, produces = "application/json")
    public ResponseEntity getItemResourceJson(
            RootResourceInformation resourceInformation,
            @BackendId Serializable id,
            OutputStream output
    ) throws HttpRequestMethodNotSupportedException, JsonProcessingException, IOException, NoSuchFieldException, IllegalArgumentException, IllegalAccessException {

        resourceInformation.verifySupportedMethod(HttpMethod.GET, ResourceType.ITEM);

        RepositoryInvoker repoMethodInvoker = resourceInformation.getInvoker();

        Object domainObj = repoMethodInvoker.invokeFindOne(id);

        if (domainObj == null) {
            return new ResponseEntity(HttpStatus.NOT_FOUND);
        }

        processOutput(resourceInformation.getResourceMetadata(), domainObj, output);

        return new ResponseEntity(HttpStatus.OK);
    }

}
