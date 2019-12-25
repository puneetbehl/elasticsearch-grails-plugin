/*
 * Copyright 2002-2010 the original author or authors.
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
package grails.plugins.elasticsearch.mapping

import grails.plugins.GrailsPluginManager
import grails.util.GrailsNameUtils
import grails.util.Holders
import groovy.transform.CompileStatic
import org.apache.commons.logging.Log
import org.apache.commons.logging.LogFactory
import org.springframework.util.ClassUtils

import java.time.OffsetDateTime
import java.time.OffsetTime
import java.time.ZonedDateTime
import java.time.temporal.Temporal

/**
 * Build ElasticSearch class mapping based on attributes provided by closure.
 */
@CompileStatic
class ElasticSearchMappingFactory {
    private static Log log = LogFactory.getLog(ElasticSearchMappingFactory)

    private static final Set<String> SUPPORTED_FORMAT =
            ['text', 'integer', 'long', 'float', 'double', 'boolean', 'null', 'date', 'keyword'] as Set<String>

    static Map<String, String> javaPrimitivesToElastic =
            [int: 'integer', long: 'long', short: 'short', double: 'double', float: 'float', byte: 'byte']

    private static Set<Class> DATE_CLASSES = [Date, Temporal] as Set<Class>
    static {
        try {
            DATE_CLASSES.add(Class.forName('org.joda.time.ReadableInstant'))
        } catch (ClassNotFoundException e) {
        }
    }

    static Map<String, Object> getElasticMapping(SearchableClassMapping scm) {
        Map mappingFields = [properties: getMappingProperties(scm)]

        // _all is deprecated in ES 6
        /*if (scm.@all instanceof Map) {
            mappingFields.put('_all', scm.@all as Map)
        }
        if (!scm.isAll()) {
            mappingFields.put('_all', [enabled: false] as Map)
        }*/

        SearchableClassPropertyMapping parentProperty = scm.propertiesMapping.find { it.parent }
        if (parentProperty) {
            mappingFields.put('_parent', [type: GrailsNameUtils.getPropertyName(parentProperty.grailsProperty.type)] as Map)
        }

        Map<String, Object> mapping = [:]
        mapping.put("${scm.getElasticTypeName()}" as String,  mappingFields)
        mapping
    }

    private static Map<String, Object> getMappingProperties(SearchableClassMapping scm) {
        Map<String, Object> elasticTypeMappingProperties = [:]

        // Map each domain properties in supported format, or object for complex type
        scm.getPropertiesMapping().each { SearchableClassPropertyMapping scpm ->
            // Does it have custom mapping?
            Map<String, Object> propOptions = [:]
            // Add the custom mapping (searchable static property in domain model)
            propOptions.putAll(scpm.getAttributes())
            String propType = getElasticType(scpm)
            if (!scpm.isGeoPoint()) {
                if (scpm.isComponent()) {
                    // Proceed with nested mapping.
                    // todo limit depth to avoid endless recursion?
                    //noinspection unchecked
                    def elasticMapping = getElasticMapping(scpm.getComponentPropertyMapping())
                    def typeName = GrailsNameUtils.getPropertyName(scpm.getGrailsProperty().getReferencedPropertyType())
                    def componentMapping = elasticMapping[typeName] as Map<String, Object>
                    if(componentMapping?.containsKey('_all')){
                        log.warn("Ignoring _all from component ${scpm.propertyName} in ${scm.elasticTypeName}")
                        componentMapping.remove('_all')
                    }
                    propOptions.putAll((Map<String, Object>)
                            (elasticMapping.values().iterator().next()))
                }

                // Once it is an object, we need to add id & class mappings, otherwise
                // ES will fail with NullPointer.
                if (scpm.isComponent() || scpm.getReference() != null) {
                    Map<String, Object> props = (Map<String, Object>) propOptions.'properties'
                    if (props == null) {
                        props = [:]
                        propOptions.properties = props
                    }
                    DomainEntity referencedDomainClass = scpm.grailsProperty.getReferencedDomainEntity() ?: scpm.getComponentPropertyMapping().domainClass
                    DomainProperty idProperty = referencedDomainClass.getPropertyByName('id')
                    String idType = idProperty.getTypePropertyName()

                    if (idTypeIsMongoObjectId(idType)) {
                        idType = treatValueAsAString(idType)
                    } else if (idTypeIsUUID(idType)) {
                        idType = 'text'
                    }

                    if(idType == 'text') idType = 'keyword'

                    props.put('id', defaultDescriptor(idType, 'true', true))
                    props.put('class', defaultDescriptor('keyword', 'false', true))
                    props.put('ref', defaultDescriptor('keyword', 'false', true))
                }
            }
            propOptions.type = propType
            // See http://www.elasticsearch.com/docs/elasticsearch/mapping/all_field/
            /*if (!(propType in ['object', 'attachment']) && scm.isAll()) {
                // does it make sense to include objects into _all?
                // is deprecated https://www.elastic.co/guide/en/elasticsearch/reference/6.4/mapping-all-field.html
                // if wanted then should be implemented with https://www.elastic.co/guide/en/elasticsearch/reference/6.4/mapping-all-field.html#enabling-all-field
                //propOptions.include_in_all = !scpm.shouldExcludeFromAll()
            }*/
            // todo only enable this through configuration...
            if (propType == 'text' && scpm.isDynamic()) {
                propOptions.type = 'object'
                propOptions.dynamic = true
            } else if ((propType == 'text') && scpm.isAnalyzed()) {
                propOptions.term_vector = 'with_positions_offsets'
            }
            if (scpm.isMultiField()) {
                Map<String, Object> field = new LinkedHashMap<String, Object>(propOptions)
                Map untouched = [:]
                untouched.put('type', propOptions.get('type') == 'text' ? 'keyword' : propOptions.get('type'))

                Map fields = [untouched: untouched]
                fields.put("${scpm.getPropertyName()}" as String, field)

                propOptions = [:]
                propOptions.type = propType
                propOptions.fields = fields
            }
            if (propType == 'object' && scpm.component && !scpm.innerComponent) {
                propOptions.type = 'nested'
            }
            if (propType == 'text' && scpm.fieldDataEnabled){
                propOptions.fielddata = true
            }
            if (propType == 'date') {
                if (scpm.grailsProperty.type == ZonedDateTime || scpm.grailsProperty.type == OffsetDateTime) {
                    propOptions.format = 'strict_date_time'
                }
                if (scpm.grailsProperty.type == OffsetTime) {
                    propOptions.format = 'strict_time'
                }
            }
            elasticTypeMappingProperties.put(scpm.getPropertyName(), propOptions)
        }
        elasticTypeMappingProperties
    }

    private static String getElasticType(SearchableClassPropertyMapping scpm) {
        String propType

        if (scpm.isGeoPoint()) {
            propType = 'geo_point'
        } else if (scpm.isAttachment()) {
            propType = 'binary'
        } else {
            DomainProperty property = scpm.grailsProperty

            propType = property.typePropertyName

            //Preprocess collections and arrays to work with it's element types
            Class referencedPropertyType = property.referencedPropertyType

            if (Collection.isAssignableFrom(referencedPropertyType) || referencedPropertyType.isArray()) {
                //Handle collections explictly mapped (needed for dealing with transients)
                Class<?> associationClass = property.getAssociationType()
                if (associationClass) {
                    referencedPropertyType = associationClass
                }
                if (referencedPropertyType.isArray()) {
                    referencedPropertyType = referencedPropertyType.getComponentType()
                }
                String basicType = getTypeSimpleName(referencedPropertyType, scpm)
                if (SUPPORTED_FORMAT.contains(basicType)) {
                    propType = basicType
                }
            } else if (!SUPPORTED_FORMAT.contains(propType) && SUPPORTED_FORMAT.contains(getTypeSimpleName(referencedPropertyType, scpm))) {
                propType = getTypeSimpleName(referencedPropertyType, scpm)
            }

            //Handle unsupported types
            if (!(SUPPORTED_FORMAT.contains(propType))) {
                if (isDateType(referencedPropertyType)) {
                    propType = 'date'
                } else if (referencedPropertyType.isEnum()) {
                    propType = 'text'
                } else if (scpm.getConverter() != null) {
                    // Use 'text' type for properties with custom converter.
                    // Arrays are automatically resolved by ElasticSearch, so no worries.
                    def requestedConverter = scpm.getConverter()
                    propType = (SUPPORTED_FORMAT.contains(requestedConverter)) ? requestedConverter : 'text'
                    // Handle primitive types, see https://github.com/mstein/elasticsearch-grails-plugin/issues/61
                } else if (referencedPropertyType.isPrimitive()) {
                    if (javaPrimitivesToElastic.containsKey(referencedPropertyType.toString())) {
                        propType = javaPrimitivesToElastic.get(referencedPropertyType.toString())
                    } else {
                        propType = 'object'
                    }
                } else if (isBigDecimalType(referencedPropertyType)) {
                    propType = 'double'
                } else {
                    propType = 'object'
                }

                if (scpm.getReference() != null) {
                    propType = 'object'      // fixme: think about composite ids.
                } else if (scpm.isComponent()) {
                    // Proceed with nested mapping.
                    // todo limit depth to avoid endless recursion?
                    propType = 'object'
                }
            }
        }

        propType
    }

    private static String getTypeSimpleName(Class type, SearchableClassPropertyMapping scpm) {
        String name = ClassUtils.getShortName(type).toLowerCase(Locale.ENGLISH)
        if (name == 'string'){
            name = scpm.analyzed ? 'text' : 'keyword'
        }
        return name
    }

    private static boolean idTypeIsMongoObjectId(String idType) {
        idType.equals('objectId')
    }

    private static boolean idTypeIsUUID(String idType) {
        idType.equalsIgnoreCase('uuid')
    }

    private static String treatValueAsAString(String idType) {
        if ((Holders.grailsApplication.config.elasticSearch as ConfigObject).datastoreImpl =~ /mongo/) {
            idType = 'text'
        } else {
            def pluginManager = Holders.applicationContext.getBean(GrailsPluginManager.BEAN_NAME)
            if (((GrailsPluginManager) pluginManager).hasGrailsPlugin('mongodb')) {
                idType = 'text'
            }
        }
        idType
    }

    private static boolean isDateType(Class type) {
        DATE_CLASSES.any { it.isAssignableFrom(type) }
    }

    private static boolean isBigDecimalType(Class type) {
        BigDecimal.isAssignableFrom(type)
    }

    private static Map<String, Object> defaultDescriptor(String type, String index, boolean excludeFromAll) {
        [type: type, index: index] as Map<String, Object>
    }
}
