/*
 * Copyright 2002-2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
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

package de.cgoit.grails.plugins.elasticsearch.mapping

import grails.core.GrailsApplication
import groovy.transform.CompileStatic
import groovy.transform.TypeCheckingMode
import org.apache.commons.logging.Log
import org.apache.commons.logging.LogFactory
import org.springframework.util.Assert

import java.lang.reflect.Modifier

@CompileStatic
class SearchableDomainClassMapper extends GroovyObjectSupport {

    private Log log = LogFactory.getLog(SearchableDomainClassMapper)

    /**
     * Class mapping properties
     */
    private all = true
    private Boolean root = true

    private Set<String> mappableProperties = new HashSet<>()
    private Map<String, SearchableClassPropertyMapping> customMappedProperties = new HashMap<>()

    private final DomainReflectionService reflectionService
    private final GrailsApplication grailsApplication
    private final ConfigObject esConfig
    private final DomainEntity domainClass

    private only
    private except

    /**
     * Create closure-based mapping configurator.
     *
     * @param grailsApplication grails app reference
     * @param domainClass Grails domain class to be configured
     * @param esConfig ElasticSearch configuration
     */
    SearchableDomainClassMapper(GrailsApplication grailsApplication,
            DomainReflectionService reflectionService,
            DomainEntity domainClass,
            ConfigObject esConfig) {
        this.grailsApplication = grailsApplication
        this.reflectionService = reflectionService
        this.esConfig = esConfig
        this.domainClass = domainClass
    }

    void setAll(all) {
        this.all = all
    }

    void setRoot(Boolean root) {
        this.root = root
    }

    void setOnly(only) {
        this.only = only
    }

    void setExcept(except) {
        this.except = except
    }

    void root(Boolean rootFlag) {
        this.root = rootFlag
    }

    /**
     * @return searchable domain class mapping
     */
    @CompileStatic(TypeCheckingMode.SKIP)
    SearchableClassMapping buildClassMapping() {
        String searchablePropertyName = getSearchablePropertyName()

        if (!domainClass.hasSearchableProperty(searchablePropertyName)) {
            return null
        }

        // Process inheritance.
        List<DomainEntity> superMappings = []
        Class<?> currentClass = domainClass.type
        superMappings.add(domainClass)

        while (currentClass != null) {
            currentClass = currentClass.superclass
            if (currentClass != null && reflectionService.isDomainEntity(currentClass)) {
                DomainEntity superDomainClass = reflectionService.getDomainEntity(currentClass)

                // If the super class is abstract, it needs peculiar processing
                // The abstract class won't be actually mapped to ES, but the concrete subclasses will have to inherit
                // the searchable mapping options.
                if (superDomainClass == null && Modifier.isAbstract(currentClass.modifiers)) {
                    // We create a temporary dummy GrailsDomainClass instance for this abstract class
                    superDomainClass = reflectionService.getAbstractDomainEntity(currentClass)
                } else {
                    // If superDomainClass is null & not abstract, then we won't process this class
                    break
                }

                if (superDomainClass.hasSearchableProperty(searchablePropertyName) &&
                        superDomainClass.getInitialPropertyValue(searchablePropertyName) == Boolean.FALSE) {
                    // hierarchy explicitly terminated. Do not browse any more properties.
                    break
                }
                superMappings.add(superDomainClass)
                if (superDomainClass.root) {
                    break
                }
            }
        }

        Collections.reverse(superMappings)

        for (DomainProperty prop : getDomainProperties(domainClass)) {
            mappableProperties.add(prop.name)
        }

        // !!!! Allow explicit identifier indexing ONLY when defined with custom attributes.
        mappableProperties.add(domainClass.identifierName)

        log.debug("Identified the following properties as candidates for mapping: $mappableProperties")

        // Process inherited mappings in reverse order.
        for (DomainEntity domainClass : superMappings) {
            if (domainClass.hasSearchableProperty(searchablePropertyName)) {
                Object searchable = domainClass.getInitialPropertyValue(searchablePropertyName)
                if (searchable instanceof Boolean) {
                    buildDefaultMapping(domainClass)
                } else if (searchable instanceof LinkedHashMap) {
                    Set<String> inheritedProperties = getInheritedProperties(domainClass)
                    buildHashMapMapping((LinkedHashMap) searchable, domainClass, inheritedProperties)
                } else if (searchable instanceof Closure) {
                    Set<String> inheritedProperties = getInheritedProperties(domainClass)
                    buildClosureMapping(domainClass, searchable as Closure, inheritedProperties)
                } else {
                    throw new IllegalArgumentException(
                            "'$searchablePropertyName' property has unknown type: ${searchable.getClass()}")
                }
            }
        }

        // Populate default settings.
        // Clean out any per-property specs not allowed by 'only', 'except' rules.

        customMappedProperties.keySet().retainAll(mappableProperties)
        mappableProperties.remove(domainClass.identifier.name)

        for (String propertyName : mappableProperties) {
            SearchableClassPropertyMapping scpm = customMappedProperties.get(propertyName)
            if (scpm == null) {
                scpm = new SearchableClassPropertyMapping(domainClass.getPropertyByName(propertyName))
                customMappedProperties.put(propertyName, scpm)
            }
        }

        SearchableClassMapping scm = new SearchableClassMapping(grailsApplication, domainClass,
                customMappedProperties.values())
        scm.root = root
        scm.all = all
        return scm
    }

    private Set<String> getInheritedProperties(DomainEntity domainClass) {
        getDomainProperties(domainClass).findAll { domainClass.isPropertyInherited(it) }
                .collect { it.name }
                .toSet()
    }

    void buildDefaultMapping(DomainEntity domainClass) {
        List<String> defaultExcludedProperties = (List<String>) esConfig.get("defaultExcludedProperties")

        for (DomainProperty property : getDomainProperties(domainClass)) {
            if (defaultExcludedProperties == null || !defaultExcludedProperties.contains(property.name)) {
                customMappedProperties.put(property.name, new SearchableClassPropertyMapping(property))
            }
        }
    }

    void buildClosureMapping(DomainEntity domainClass, Closure searchable, Set<String> inheritedProperties) {
        assert searchable != null

        // Build user-defined specific mappings
        Closure closure = (Closure) searchable.clone()
        closure.setDelegate(this)
        closure.call()

        buildMappingFromOnlyExcept(domainClass, inheritedProperties)
    }

    void buildHashMapMapping(LinkedHashMap map, DomainEntity domainClass, Set<String> inheritedProperties) {
        // Support old searchable-plugin syntax ([only: ['category', 'title']] or [except: 'createdAt'])
        only = map.containsKey("only") ? map.get("only") : null
        except = map.containsKey("except") ? map.get("except") : null
        buildMappingFromOnlyExcept(domainClass, inheritedProperties)
    }

    private void buildMappingFromOnlyExcept(DomainEntity domainClass, Set<String> inheritedProperties) {
        Set<String> propsOnly = convertToSet(only)
        Set<String> propsExcept = convertToSet(except)
        if (!propsOnly.empty && !propsExcept.empty) {
            throw new IllegalArgumentException(
                    "Both 'only' and 'except' were used in '${this.domainClass.defaultPropertyName}#${searchablePropertyName}': provide one or neither but not both")
        }

        Boolean alwaysInheritProperties = (Boolean) esConfig.get("alwaysInheritProperties")
        boolean inherit = alwaysInheritProperties != null && alwaysInheritProperties

        def defaultExcludedProperties = esConfig.get("defaultExcludedProperties")
        if (defaultExcludedProperties instanceof Collection) {
            log.
                    debug("Removing default excluded properties ${defaultExcludedProperties} from mappable properties for class ${domainClass.type} : ${mappableProperties}")
            mappableProperties.removeAll(defaultExcludedProperties)
        }

        // Remove all properties that may be in the "except" rule
        if (!propsExcept.isEmpty()) {
            log.debug("'except' found on class ${domainClass.type}. Removing properties from mappings : ${propsExcept}")
            mappableProperties.removeAll(propsExcept)
        }
        // Only keep the properties specified in the "only" rule
        if (!propsOnly.isEmpty()) {
            log.debug("'only' found on class ${domainClass.type}.")
            // If we have inherited properties, we keep them nonetheless
            if (inherit) {
                log.
                        debug("'only' found on class ${domainClass.type}. Keeping inherited properties : ${inheritedProperties}")
                mappableProperties.retainAll(inheritedProperties)
            } else {
                mappableProperties.clear()
            }
            log.debug("Including properties defined in 'only' : ${propsOnly}")
            mappableProperties.addAll(propsOnly)
        }

        log.debug("Properties to map for class ${domainClass.type} are: ${mappableProperties}")
    }

    /**
     * Invoked by 'searchable' closure.
     *
     * @param name synthetic method name
     * @param args method arguments.
     * @return <code> null</code>
     */
    def invokeMethod(String name, args) {
        // Custom properties mapping options
        DomainProperty property = domainClass.getPropertyByName(name)
        Assert.notNull(property,
                "Unable to find property [$name] used in [$domainClass.defaultPropertyName]#${searchablePropertyName}].")

        // Check if we already has mapping for this property.
        SearchableClassPropertyMapping propertyMapping = customMappedProperties.get(name)
        if (propertyMapping == null) {
            propertyMapping = new SearchableClassPropertyMapping(property)
            customMappedProperties.put(name, propertyMapping)
        }
        //noinspection unchecked
        def attributes = (Map<String, Object>) ((Object[]) args)[0]
        if (attributes?.containsKey('multi_field')) {
            boolean addUntouched = attributes.multi_field
            attributes.remove('multi_field')
            if (addUntouched) {
                attributes.
                        fields = (LinkedHashMap<String, LinkedHashMap<String, String>>) (['untouched': ['type': 'keyword']])
                // To preserve compatibility with ElasticSearchMappingFactory
            }
        }
        propertyMapping.addAttributes(attributes)
        return null
    }

    private static Set<String> convertToSet(arg) {
        if (arg == null) {
            return Collections.emptySet()
        }
        if (arg instanceof String) {
            return Collections.singleton(arg) as Set<String>
        }
        if (arg instanceof Object[]) {
            return new HashSet<String>(Arrays.asList(arg as String[]))
        }
        if (arg instanceof Collection) {
            //noinspection unchecked
            Set<String> set = new HashSet<>()
            set.addAll(arg)
            return set
        }
        throw new IllegalArgumentException("Unknown argument: $arg")
    }

    private String getSearchablePropertyName() {
        def searchablePropertyName = (esConfig.searchableProperty as ConfigObject).name

        //Maintain backwards compatibility. Searchable property name may not be defined
        if (!searchablePropertyName) {
            return 'searchable'
        }
        searchablePropertyName
    }

    //These properties are specific to GORM and of no use for search. For backwards compatibility they are not included
    private static Set<String> EXCLUDED_PROPERTIES = ["id", "version"] as Set

    private static Collection<DomainProperty> findNonExcludedProperties(DomainEntity domainClass) {
        domainClass.properties.findAll { !EXCLUDED_PROPERTIES.contains(it.name) }
    }

    private Collection<DomainProperty> getDomainProperties(DomainEntity domainClass) {
        return esConfig.includeTransients ? findNonExcludedProperties(domainClass)
                                          : domainClass.persistentProperties
    }
}
