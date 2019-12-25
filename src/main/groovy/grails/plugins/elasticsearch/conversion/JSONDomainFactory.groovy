/*
 * Copyright 2002-2010 the original author or authors.
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

package grails.plugins.elasticsearch.conversion

import grails.core.GrailsApplication
import grails.plugins.elasticsearch.ElasticSearchContextHolder
import grails.plugins.elasticsearch.conversion.marshall.*
import grails.plugins.elasticsearch.mapping.DomainEntity
import grails.plugins.elasticsearch.mapping.DomainReflectionService
import grails.plugins.elasticsearch.mapping.SearchableClassMapping
import grails.plugins.elasticsearch.unwrap.DomainClassUnWrapperChain
import org.elasticsearch.common.xcontent.XContentBuilder

import java.beans.PropertyEditor
import java.sql.Timestamp

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder

/**
 * Marshall objects as JSON.
 */
class JSONDomainFactory {

    ElasticSearchContextHolder elasticSearchContextHolder
    GrailsApplication grailsApplication
    DomainClassUnWrapperChain domainClassUnWrapperChain
    DomainReflectionService domainReflectionService

    /**
     * The default marshallers, not defined by user
     */
    static Map<Class<?>, Class<? extends DefaultMarshaller>> DEFAULT_MARSHALLERS = [
            (Map)       : MapMarshaller,
            (Collection): CollectionMarshaller
    ]

    /**
     * Create and use the correct marshaller for a peculiar class
     * @param object The instance to marshall
     * @param marshallingContext The marshalling context associate with the current marshalling process
     * @return Object The result of the marshall operation.
     */
    Object delegateMarshalling(Object object, DefaultMarshallingContext marshallingContext, int maxDepth = 0) {
        if (object == null) {
            return null
        }

        DefaultMarshaller marshaller = null
        Object unwrappedObject = domainClassUnWrapperChain.unwrap(object)
        Class<?> objectClass = unwrappedObject.getClass()

        // Resolve collections.
        // Check for direct marshaller matching
        if (unwrappedObject instanceof Collection) {
            marshaller = new CollectionMarshaller()
        }

        if (!marshaller && DEFAULT_MARSHALLERS[objectClass]) {
            marshaller = DEFAULT_MARSHALLERS[objectClass].newInstance()
        }

        if (!marshaller) {
            // Check if we arrived from searchable domain class.
            Object parentObject = marshallingContext.peekDomainObject()
            if (parentObject && marshallingContext.lastParentPropertyName && isDomainClass(parentObject.class)) {
                DomainEntity domainClass = getUnwrappedInstanceDomainClass(parentObject)
                def propertyMapping = elasticSearchContextHolder.getMappingContext(domainClass)?.getPropertyMapping(marshallingContext.lastParentPropertyName)
                def converter = propertyMapping?.converter
                // Property has converter information. Lets see how we can apply it.
                if (converter) {
                    // Property editor?
                    if (converter instanceof Class) {
                        if (PropertyEditor.isAssignableFrom(converter)) {
                            marshaller = new PropertyEditorMarshaller(propertyEditorClass: converter)
                        }
                    }
                } else if (propertyMapping?.isDynamic()) {
                    marshaller = new DynamicValueMarshaller()
                } else if (propertyMapping?.reference) {
                    def refClass = propertyMapping.getBestGuessReferenceType()
                    marshaller = new SearchableReferenceMarshaller(refClass: refClass)
                } else if (propertyMapping?.component) {
                    if (propertyMapping?.isGeoPoint()) {
                        marshaller = new GeoPointMarshaller()
                    } else {
                        marshaller = new DeepDomainClassMarshaller()
                    }
                }
            }
        }

        if (!marshaller) {
            // TODO : support user custom marshaller/converter (& marshaller registration)
            // Check for domain classes
            if (isDomainClass(objectClass)) {
                def propertyMapping = elasticSearchContextHolder.getMappingContext(getUnwrappedInstanceDomainClass(marshallingContext.peekDomainObject()))?.getPropertyMapping(marshallingContext.lastParentPropertyName)

                if (propertyMapping?.isGeoPoint()) {
                    marshaller = new GeoPointMarshaller()
                } else {
                    marshaller = new DeepDomainClassMarshaller()
                }
            } else {
                // Check for inherited marshaller matching
                def inheritedMarshaller = DEFAULT_MARSHALLERS.find { key, value -> key.isAssignableFrom(objectClass) }
                if (inheritedMarshaller) {
                    marshaller = DEFAULT_MARSHALLERS[inheritedMarshaller.key].newInstance()
                    // If no marshaller was found, use the default one
                } else {
                    marshaller = new DefaultMarshaller()
                }
            }
        }

        marshaller.marshallingContext = marshallingContext
        marshaller.elasticSearchContextHolder = elasticSearchContextHolder
        marshaller.grailsApplication = grailsApplication
        marshaller.domainClassUnWrapperChain = domainClassUnWrapperChain
        marshaller.maxDepth = maxDepth
        marshaller.marshall(unwrappedObject)
    }

    /**
     * Build an XContentBuilder representing a domain instance in JSON.
     * Use as a source to an index request to ElasticSearch.
     * @param instance A domain class instance.
     * @return
     */
    XContentBuilder buildJSON(Object instance) {
        DomainEntity domainClass = getInstanceDomainClass(instance)
        XContentBuilder json = jsonBuilder().startObject()
        // TODO : add maxDepth in custom mapping (only for "searchable components")
        SearchableClassMapping scm = elasticSearchContextHolder.getMappingContext(domainClass)

        DefaultMarshallingContext marshallingContext = new DefaultMarshallingContext(maxDepth: 5, parentFactory: this)
        marshallingContext.push(instance)

        // Build the json-formated map that will contain the data to index
        scm.propertiesMapping.each { scpm ->
            marshallingContext.lastParentPropertyName = scpm.propertyName
            Object res = delegateMarshalling(instance."${scpm.propertyName}", marshallingContext)

            // convert Date to UTC Date: https://www.elastic.co/guide/en/elasticsearch/reference/6.5/date.html
            if (res?.class == Timestamp) {
                TimeZone.setDefault(TimeZone.getTimeZone('UTC'))
                res = res.toCalendar().getTime().format("yyyy-MM-dd'T'HH:mm:ss'Z'")
            }

            json.field(scpm.propertyName, res)
            // add the alias
            if (scpm.getAlias()) {
                json.field(scpm.getAlias(), res)
            }
        }
        marshallingContext.pop()
        json.endObject()
        json.close()
        json
    }

    boolean isDomainClass(Class<?> clazz) {
        domainReflectionService.isDomainEntity(clazz)
    }

    DomainEntity getInstanceDomainClass(Object instance) {
        domainReflectionService.getDomainEntity(instance.class)
    }

    DomainEntity getUnwrappedInstanceDomainClass(Object instance) {
        getInstanceDomainClass(domainClassUnWrapperChain.unwrap(instance))
    }

}
