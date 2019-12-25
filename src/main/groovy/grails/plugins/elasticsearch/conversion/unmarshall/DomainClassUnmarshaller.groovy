/*
 * Copyright 2002-2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package grails.plugins.elasticsearch.conversion.unmarshall

import grails.converters.JSON
import grails.core.GrailsApplication
import grails.plugins.elasticsearch.ElasticSearchContextHolder
import grails.plugins.elasticsearch.exception.MappingException
import grails.plugins.elasticsearch.mapping.DomainEntity
import grails.plugins.elasticsearch.mapping.DomainProperty
import grails.plugins.elasticsearch.mapping.SearchableClassMapping
import grails.plugins.elasticsearch.mapping.SearchableClassPropertyMapping
import grails.web.databinding.DataBinder

import java.time.OffsetDateTime
import java.time.OffsetTime
import java.time.temporal.Temporal
import java.time.format.DateTimeFormatter as JDateTimeFormatter
import org.codehaus.groovy.runtime.DefaultGroovyMethods
import org.elasticsearch.action.get.GetRequest
import org.elasticsearch.action.get.GetResponse
import org.elasticsearch.client.RequestOptions
import org.elasticsearch.client.RestHighLevelClient
import org.elasticsearch.search.SearchHit
import org.elasticsearch.search.SearchHits
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.joda.time.format.DateTimeFormat
import org.joda.time.format.DateTimeFormatter
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.SimpleTypeConverter
import org.springframework.beans.TypeConverter
import org.springframework.util.Assert

import java.beans.PropertyEditor
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime

/**
 * Domain class unmarshaller.
 */
class DomainClassUnmarshaller implements DataBinder {

    private static final Logger LOG = LoggerFactory.getLogger(this)

    private ElasticSearchContextHolder elasticSearchContextHolder
    private GrailsApplication grailsApplication
    private RestHighLevelClient elasticSearchClient

    Collection buildResults(SearchHits hits) {
        DefaultUnmarshallingContext unmarshallingContext = new DefaultUnmarshallingContext()
        TypeConverter typeConverter = new SimpleTypeConverter()
        List results = []
        for (SearchHit hit : hits) {
            String type = hit.type
            SearchableClassMapping scm = elasticSearchContextHolder.findMappingContextByElasticType(type)
            if (scm == null) {
                LOG.warn("Unknown SearchHit: ${hit.id}#${hit.type}: adding to result set as a raw object")
                results << hit.sourceAsMap
                continue
            }

            DomainProperty identifier = scm.domainClass.identifier
            Object id = typeConverter.convertIfNecessary(hit.id, identifier.type)
            GroovyObject instance = (GroovyObject) scm.domainClass.type.newInstance()
            instance.setProperty(identifier.name, id)

            def aliasFields = elasticSearchContextHolder.getMappingContext(scm.domainClass).getPropertiesMapping().findResults {
                if (it.isAlias()) {
                    return it.getAlias()
                }
                null
            }

            Map rebuiltProperties = new HashMap()
            for (Map.Entry<String, Object> entry : hit.sourceAsMap.entrySet()) {
                def key = entry.key
                if (aliasFields.contains(key)) {
                    continue
                }
                try {
                    unmarshallingContext.unmarshallingStack.push(key)
                    def unmarshalledProperty = unmarshallProperty(scm.domainClass, key, entry.value, unmarshallingContext)
                    rebuiltProperties[key] = unmarshalledProperty
                    populateCyclicReference(instance, rebuiltProperties, unmarshallingContext)
                } catch (MappingException e) {
                    LOG.debug("Error unmarshalling property '$key' of Class ${scm.domainClass.type.name} with id $id", e)
                } catch (Throwable t) {
                    LOG.error("Error unmarshalling property '$key' of Class ${scm.domainClass.type.name} with id $id", t)
                } finally {
                    unmarshallingContext.resetContext()
                }
            }
            bindData(instance, rebuiltProperties)

            results << instance
        }
        results
    }

    private void populateCyclicReference(instance, Map<String, Object> rebuiltProperties, DefaultUnmarshallingContext unmarshallingContext) {
        for (CycleReferenceSource cr : unmarshallingContext.cycleRefStack) {
            populateProperty(cr.cyclePath, rebuiltProperties, resolvePath(cr.sourcePath, instance, rebuiltProperties))
        }
    }

    private resolvePath(String path, instance, Map<String, Object> rebuiltProperties) {
        if (!path || path == '') {
            return instance
        }
        StringTokenizer st = new StringTokenizer(path, '/')
        Object currentProperty = rebuiltProperties
        while (st.hasMoreTokens()) {
            String part = st.nextToken()
            try {
                int index = Integer.parseInt(part)
                currentProperty = DefaultGroovyMethods.getAt(DefaultGroovyMethods.asList((Collection) currentProperty), index)
            } catch (NumberFormatException e) {
                currentProperty = DefaultGroovyMethods.getAt(currentProperty, part)
            }
        }
        currentProperty
    }

    private void populateProperty(String path, Map<String, Object> rebuiltProperties, value) {
        String last
        Object currentProperty = rebuiltProperties
        StringTokenizer st = new StringTokenizer(path, '/')
        int size = st.countTokens()
        int index = 0
        while (st.hasMoreTokens()) {
            String part = st.nextToken()
            if (index < size - 1) {
                try {
                    if (currentProperty instanceof Collection) {
                        //noinspection unchecked
                        currentProperty = DefaultGroovyMethods.getAt(((Collection<Object>) currentProperty).iterator(), DefaultGroovyMethods.toInteger(part))
                    } else {
                        currentProperty = DefaultGroovyMethods.getAt(currentProperty, part)
                    }
                } catch (Exception e) {
                    LOG.warn("/!\\ Error when trying to populate $path")
                    LOG.warn("Cannot get $part on $currentProperty from $rebuiltProperties")
                }
            }
            if (!st.hasMoreTokens()) {
                last = part
            }
            index++
        }
        try {
            Integer.parseInt(last)
            ((Collection) currentProperty).add(value)
        } catch (NumberFormatException e) {
            DefaultGroovyMethods.putAt(currentProperty, last, value)
        }
    }

    private unmarshallProperty(DomainEntity domainClass, String propertyName, propertyValue, DefaultUnmarshallingContext unmarshallingContext) {
        // TODO : adapt behavior if the mapping option "component" or "reference" are set
        // below is considering the "component" behavior
        SearchableClassPropertyMapping scpm = elasticSearchContextHolder.getMappingContext(domainClass).getPropertyMapping(propertyName)
        Object parseResult
        if (scpm == null) {
            throw new MappingException("Property ${domainClass.type.simpleName}.${propertyName} found in index, but is not defined as searchable.")
        }

        if (scpm?.dynamic && null != propertyValue) {
            parseResult = (propertyValue as JSON).toString()
        } else if (null != scpm && propertyValue instanceof Map) {

            Map<String, Object> data = (Map<String, Object>) propertyValue

            // Handle cycle reference
            if (data.containsKey("ref")) {
                unmarshallingContext.addCycleRef(propertyValue)
                return null
            }

            // Searchable reference.
            if (scpm.reference != null) {
                Class<?> refClass = scpm.bestGuessReferenceType
                DomainEntity refDomainClass = elasticSearchContextHolder.getMappingContextByType(refClass)?.domainClass
                Assert.state(refDomainClass != null, "Found reference to non-domain class: $refClass")
                return unmarshallReference(refDomainClass, data, unmarshallingContext)
            }

            if (data.containsKey("class") && (Boolean) grailsApplication.config.get('elasticSearch.unmarshallComponents')) {
                // Embedded instance.
                if (!scpm.isComponent()) {
                    // maybe ignore?
                    throw new IllegalStateException("Property ${domainClass.type.name}.${propertyName} is not mapped as [component], but broken search hit found.")
                }
                DomainEntity nestedDomainClass = elasticSearchContextHolder.getMappingContext((String) data.get('class'))?.domainClass
                if (domainClass != null) {
                    // Unmarshall 'component' instance.
                    if (!scpm.isComponent()) {
                        throw new IllegalStateException("Object ${data.get('class')} found in index, but [$propertyName] is not mapped as component.")
                    }
                    parseResult = unmarshallDomain(nestedDomainClass, data.get('id'), data, unmarshallingContext)
                }
            }
        } else if (propertyValue instanceof Collection) {
            List<Object> results = []
            int index = 0
            for (innerValue in (Collection) propertyValue) {
                unmarshallingContext.unmarshallingStack.push(String.valueOf(index))
                Object parseItem = unmarshallProperty(domainClass, propertyName, innerValue, unmarshallingContext)
                if (parseItem != null) {
                    results.add(parseItem)
                }
                index++
                unmarshallingContext.unmarshallingStack.pop()
            }
            parseResult = results
        } else {
            // consider any custom property editors here.
            if (scpm.converter != null) {
                if (scpm.converter instanceof Class) {
                    try {
                        PropertyEditor propertyEditor = (PropertyEditor) ((Class) scpm.converter).newInstance()
                        propertyEditor.setAsText((String) propertyValue)
                        parseResult = propertyEditor.value
                    } catch (Exception e) {
                        throw new IllegalArgumentException("Unable to unmarshall $propertyName using $scpm.converter", e)
                    }
                }
            } else if (scpm.reference != null) {

                // This is a reference and it MUST be null because it's not a Map.
                if (propertyValue != null) {
                    throw new IllegalStateException("Found searchable reference which is not a Map: ${domainClass}.${propertyName} = $propertyValue")
                }

                parseResult = null
            } else if (scpm.grailsProperty.type == Date && propertyValue != null) {
                DateTimeFormatter dateTimeFormatter = DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(DateTimeZone.UTC)
                parseResult = DateTime.parse(propertyValue, dateTimeFormatter)
            } else if (Temporal.isAssignableFrom(scpm.grailsProperty.type) && propertyValue != null) {
                switch (scpm.grailsProperty.type) {
                    case LocalDate:
                        JDateTimeFormatter dateTimeFormatter = JDateTimeFormatter.ISO_LOCAL_DATE
                        parseResult = LocalDate.parse(propertyValue, dateTimeFormatter)
                        break
                    case LocalDateTime:
                        JDateTimeFormatter dateTimeFormatter = JDateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSX")
                        parseResult = LocalDateTime.parse(propertyValue, dateTimeFormatter)
                        break
                    case ZonedDateTime:
                        JDateTimeFormatter dateTimeFormatter = JDateTimeFormatter.ISO_OFFSET_DATE_TIME
                        parseResult = ZonedDateTime.parse(propertyValue, dateTimeFormatter)
                        break
                    case OffsetDateTime:
                        JDateTimeFormatter dateTimeFormatter = JDateTimeFormatter.ISO_OFFSET_DATE_TIME
                        parseResult = OffsetDateTime.parse(propertyValue, dateTimeFormatter)
                        break
                    case OffsetTime:
                        JDateTimeFormatter dateTimeFormatter = JDateTimeFormatter.ISO_OFFSET_TIME
                        parseResult = OffsetTime.parse(propertyValue, dateTimeFormatter)
                        break
                }
            }
        }

        if (parseResult != null) {
            return parseResult
        }
        propertyValue
    }

    private unmarshallReference(DomainEntity domainClass, Map<String, Object> data, DefaultUnmarshallingContext unmarshallingContext) {
        // As a simplest scenario recover object directly from ElasticSearch.
        // todo add first-level caching and cycle ref checking
        String indexName = elasticSearchContextHolder.getMappingContext(domainClass).queryingIndex
        String name = elasticSearchContextHolder.getMappingContext(domainClass).elasticTypeName
        TypeConverter typeConverter = new SimpleTypeConverter()
        // A property value is expected to be a map in the form [id:ident]
        Object id = data.id
        GetRequest request = new GetRequest(indexName).id(typeConverter.convertIfNecessary(id, String))
        if (data.containsKey('parent')) {
            request.parent(typeConverter.convertIfNecessary(data.parent, String))
        }
        GetResponse response = elasticSearchClient.get(request, RequestOptions.DEFAULT)
        Map<String, Object> resolvedReferenceData = response.sourceAsMap
        Assert.state(resolvedReferenceData != null, "Could not find and resolve searchable reference: $request")
        unmarshallDomain(domainClass, response.id, resolvedReferenceData, unmarshallingContext)
    }

    private unmarshallDomain(DomainEntity domainClass, providedId, Map<String, Object> data, DefaultUnmarshallingContext unmarshallingContext) {
        DomainProperty identifier = domainClass.identifier
        TypeConverter typeConverter = new SimpleTypeConverter()
        Object id = typeConverter.convertIfNecessary(providedId, identifier.type)
        GroovyObject instance = (GroovyObject) domainClass.type.newInstance()
        instance.setProperty(identifier.name, id)
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            if (entry.key != 'class' && entry.key != 'id') {
                try {
                    unmarshallingContext.unmarshallingStack.push(entry.key)
                    Object propertyValue = unmarshallProperty(domainClass, entry.key, entry.value, unmarshallingContext)
                    bindData(instance, Collections.singletonMap(entry.key, propertyValue))
//                    TODO: Remove comment
//                    new DatabindingApi().setProperties(instance, Collections.singletonMap(entry.key, propertyValue))
                } catch (MappingException e) {
                    LOG.debug("Error unmarshalling property '${entry.key}', value= ${entry.value}", e)
                } finally {
                    unmarshallingContext.unmarshallingStack.pop()
                }
            }
        }
        instance
    }

    void setElasticSearchContextHolder(ElasticSearchContextHolder elasticSearchContextHolder) {
        this.elasticSearchContextHolder = elasticSearchContextHolder
    }

    void setGrailsApplication(GrailsApplication grailsApplication) {
        this.grailsApplication = grailsApplication
    }

    void setElasticSearchClient(RestHighLevelClient elasticSearchClient) {
        this.elasticSearchClient = elasticSearchClient
    }
}
