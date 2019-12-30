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

package grails.plugins.elasticsearch.mapping

import grails.core.GrailsApplication
import grails.plugins.elasticsearch.ElasticSearchContextHolder
import grails.plugins.elasticsearch.util.ElasticSearchConfigAware
import grails.plugins.elasticsearch.util.IndexNamingUtils
import groovy.transform.CompileStatic

@CompileStatic
class SearchableClassMapping implements ElasticSearchConfigAware {

    private final GrailsApplication grailsApplication

    /** All searchable properties */
    private Collection<SearchableClassPropertyMapping> propertiesMapping

    /** Owning domain class */
    private DomainEntity domainClass

    /** Searchable root? */
    private boolean root = true

    protected all = false // _all is deprecated in ES 6
    String indexName

    SearchableClassMapping(GrailsApplication grailsApplication,
                           DomainEntity domainClass,
                           Collection<SearchableClassPropertyMapping> propertiesMapping) {
        this.grailsApplication = grailsApplication
        this.domainClass = domainClass
        this.propertiesMapping = propertiesMapping
        this.indexName = calculateIndexName()
    }

    SearchableClassPropertyMapping getPropertyMapping(String propertyName) {
        propertiesMapping.find { it.propertyName == propertyName }
    }

    Boolean isRoot() {
        root
    }

    void setRoot(Boolean root) {
        this.root = root != null && root
    }

    void setAll(all) {
        //if (all != null)
        //    this.all = all // _all is deprecated in ES 6
    }

    Collection<SearchableClassPropertyMapping> getPropertiesMapping() {
        propertiesMapping
    }

    DomainEntity getDomainClass() {
        domainClass
    }

    /**
     * Validate searchable class mapping.
     * @param contextHolder context holding all known searchable mappings.
     */
    void validate(ElasticSearchContextHolder contextHolder) {
        for (SearchableClassPropertyMapping scpm : propertiesMapping) {
            scpm.validate(contextHolder)
        }
    }

    String calculateIndexName() {
        String name = (esConfig?.get('index') as ConfigObject)?.name ?: domainClass.fullName

        if (name == null || name.length() == 0) {
            name = domainClass.defaultPropertyName
        }

        // index name must be lowercase (org.elasticsearch.indices.InvalidIndexNameException)
        name.toLowerCase()
    }

    String getIndexingIndex() {
        return IndexNamingUtils.indexingIndexFor(indexName)
    }

    String getQueryingIndex() {
        return IndexNamingUtils.queryingIndexFor(indexName)
    }

    /**
     * @return type name for ES mapping.
     */
    String getElasticTypeName() {
        domainClass.propertyNameRepresentation
    }

    boolean isAll() {
        false
        /*if (all instanceof Boolean) {
            return all
        } else if (all instanceof Map) {
            return (all as Map).enabled instanceof Boolean ? (all as Map).enabled : true
        }
        true*/ // _all is deprecated in ES 6
    }

    @Override
    String toString() {
        return "${getClass().name}(domainClass:$domainClass, propertiesMapping:$propertiesMapping, indexName:$indexName, isAll:${isAll()})"
    }

    @Override
    GrailsApplication getGrailsApplication() {
        grailsApplication
    }

}
