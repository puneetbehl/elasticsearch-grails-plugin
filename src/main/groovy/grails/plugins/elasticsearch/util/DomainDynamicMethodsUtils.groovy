/*
 * Copyright 2002-2010 the original author or authors.
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
package grails.plugins.elasticsearch.util

import grails.core.GrailsApplication
import grails.plugins.elasticsearch.ElasticSearchContextHolder
import grails.plugins.elasticsearch.ElasticSearchService
import grails.plugins.elasticsearch.exception.IndexException
import grails.plugins.elasticsearch.mapping.DomainEntity
import grails.plugins.elasticsearch.mapping.DomainReflectionService
import grails.plugins.elasticsearch.mapping.SearchableClassMapping
import org.apache.commons.logging.LogFactory
import org.elasticsearch.index.query.QueryBuilder
import org.elasticsearch.search.aggregations.AggregationBuilder
import org.elasticsearch.search.aggregations.BaseAggregationBuilder
import org.springframework.context.ApplicationContext

class DomainDynamicMethodsUtils {

    static final LOG = LogFactory.getLog(this)

    /**
     * Injects the dynamic methods in the searchable domain classes.
     * Considers that the mapping has been resolved beforehand.
     *
     * @param grailsApplication
     * @param applicationContext
     * @return
     */
    static injectDynamicMethods(GrailsApplication grailsApplication, ApplicationContext applicationContext) {
        ElasticSearchService elasticSearchService = applicationContext.getBean(ElasticSearchService)
        ElasticSearchContextHolder elasticSearchContextHolder = applicationContext.getBean(ElasticSearchContextHolder)

        DomainReflectionService domainEntityService = applicationContext.getBean(DomainReflectionService)

        for (DomainEntity domain in domainEntityService.domainEntities) {
            String searchablePropertyName = getSearchablePropertyName(grailsApplication)

            if (!domain.getInitialPropertyValue(searchablePropertyName)) continue

            def domainCopy = domain
            // Only inject the methods if the domain is mapped as "root"
            SearchableClassMapping scm = elasticSearchContextHolder.getMappingContext(domainCopy)
            if (!scm || !scm.root) continue

            def indexAndType = [indices: scm.queryingIndex, types: domainCopy.type]

			String searchMethodName = grailsApplication.config.getProperty('elasticSearch.searchMethodName', String, 'search')
			String countHitsMethodName = grailsApplication.config.getProperty('elasticSearch.countHitsMethodName', String, 'countHits')

            // Inject the search method
            domain.delegateMetaClass.static."$searchMethodName" << { String q, Map params = [:] ->
                elasticSearchService.search(q, params + indexAndType)
            }
            domain.delegateMetaClass.static."$searchMethodName" << { Map params = [:], Closure q ->
                elasticSearchService.search(params + indexAndType, q)
            }
            domain.delegateMetaClass.static."$searchMethodName" << { Closure q, Map params = [:] ->
                elasticSearchService.search(params + indexAndType, q)
            }
            domain.delegateMetaClass.static."$searchMethodName" << { Closure q, Closure f, Map params = [:] ->
                elasticSearchService.search(q, f, null, params + indexAndType)
            }
            domain.delegateMetaClass.static."$searchMethodName" << { Closure q, Closure f, Closure a, Map params = [:] ->
                elasticSearchService.search(q, f, a, params + indexAndType)
            }
            domain.delegateMetaClass.static."$searchMethodName" << { Map params, Closure q, Closure f ->
                elasticSearchService.search(params + indexAndType, q, f)
            }
            domain.delegateMetaClass.static."$searchMethodName" << { Map params, Closure q, Closure f, Closure a ->
                elasticSearchService.search(params + indexAndType, q, f, a)
            }
            domain.delegateMetaClass.static."$searchMethodName" << { Map params, QueryBuilder q, Closure f = null, Closure a = null ->
                elasticSearchService.search(params + indexAndType, q, f, a)
            }
            domain.delegateMetaClass.static."$searchMethodName" << { QueryBuilder q, Closure f = null, Closure a = null, Map params = [:] ->
                elasticSearchService.search(q, f, a, params + indexAndType)
            }
            domain.delegateMetaClass.static."$searchMethodName" << { Closure q, f, Map params = [:] ->
                elasticSearchService.search(q, f, null, params + indexAndType)
            }
            domain.delegateMetaClass.static."$searchMethodName" << { Closure q, f, a, Map params = [:] ->
                elasticSearchService.search(q, f, a, params + indexAndType)
            }
            domain.delegateMetaClass.static."$searchMethodName" << { Map params, Closure q, f ->
                elasticSearchService.search(params + indexAndType, q, f)
            }
            domain.delegateMetaClass.static."$searchMethodName" << { Map params, Closure q, f, a ->
                elasticSearchService.search(params + indexAndType, q, f, a)
            }
            domain.delegateMetaClass.static."$searchMethodName" << { Map params, QueryBuilder q, f = null ->
                elasticSearchService.search(params + indexAndType, q, f)
            }
            domain.delegateMetaClass.static."$searchMethodName" << { Map params, QueryBuilder q, f = null, a = null ->
                elasticSearchService.search(params + indexAndType, q, f, a)
            }
            domain.delegateMetaClass.static."$searchMethodName" << { QueryBuilder q, f = null, a = null, Map params = [:] ->
                elasticSearchService.search(q, f, a, params + indexAndType)
            }
            domain.delegateMetaClass.static."$searchMethodName" << { Map params, QueryBuilder q, QueryBuilder f, a = null ->
                elasticSearchService.search(params + indexAndType, q, f, a)
            }
            domain.delegateMetaClass.static."$searchMethodName" << { QueryBuilder q, QueryBuilder f, a = null, Map params = [:] ->
                elasticSearchService.search(q, f, a, params + indexAndType)
            }
            domain.delegateMetaClass.static."$searchMethodName" << { Map params, QueryBuilder q, QueryBuilder f, BaseAggregationBuilder a ->
                elasticSearchService.search(params + indexAndType, q, f, a)
            }
            domain.delegateMetaClass.static."$searchMethodName" << { Map params, QueryBuilder q, QueryBuilder f, Collection<BaseAggregationBuilder> a ->
                elasticSearchService.search(params + indexAndType, q, f, a)
            }
            domain.delegateMetaClass.static."$searchMethodName" << { QueryBuilder q, QueryBuilder f, BaseAggregationBuilder a, Map params = [:] ->
                elasticSearchService.search(q, f, a, params + indexAndType)
            }
            domain.delegateMetaClass.static."$searchMethodName" << { QueryBuilder q, QueryBuilder f, Collection<BaseAggregationBuilder> a, Map params = [:] ->
                elasticSearchService.search(q, f, a, params + indexAndType)
            }
            domain.delegateMetaClass.static."$searchMethodName" << { QueryBuilder q, BaseAggregationBuilder a, Map params = [:] ->
                elasticSearchService.search(q, null, a, params + indexAndType)
            }
            domain.delegateMetaClass.static."$searchMethodName" << { QueryBuilder q, Collection<BaseAggregationBuilder> a, Map params = [:] ->
                elasticSearchService.search(q, null, a, params + indexAndType)
            }

            // Inject the countHits method
            domain.delegateMetaClass.static."$countHitsMethodName" << { String q, Map params = [:] ->
                elasticSearchService.countHits(q, params + indexAndType)
            }
            domain.delegateMetaClass.static."$countHitsMethodName" << { Map params = [:], Closure q ->
                elasticSearchService.countHits(params + indexAndType, q)
            }
            domain.delegateMetaClass.static."$countHitsMethodName" << { Closure q, Map params = [:] ->
                elasticSearchService.countHits(params + indexAndType, q)
            }

            // Inject the index method
            // static index() with no arguments index every instances of the domainClass
            domain.delegateMetaClass.static.index << { ->
                elasticSearchService.index(class: domainCopy.type)
            }
            // static index( domainInstances ) index every instances specified as arguments
            domain.delegateMetaClass.static.index << { Collection<GroovyObject> instances ->
                def invalidTypes = instances.any { inst ->
                    inst.class != domainCopy.type
                }
                if (!invalidTypes) {
                    elasticSearchService.index(instances)
                } else {
                    throw new IndexException("[${domainCopy.defaultPropertyName}] index() method can only be applied its own type. Please use the elasticSearchService if you want to index mixed values.")
                }
            }
            // static index( domainInstances ) index every instances specified as arguments (ellipsis styled)
            domain.delegateMetaClass.static.index << { GroovyObject... instances ->
                delegate.metaClass.invokeStaticMethod(domainCopy.type, 'index', instances as Collection<GroovyObject>)
            }
            // index() method on domain instance
            domain.delegateMetaClass.index << {
                elasticSearchService.index(delegate)
            }

            // Inject the unindex method
            // static unindex() with no arguments unindex every instances of the domainClass
            domain.delegateMetaClass.static.unindex << { ->
                elasticSearchService.unindex(class: domainCopy.clazz)
            }
            // static unindex( domainInstances ) unindex every instances specified as arguments
            domain.delegateMetaClass.static.unindex << { Collection<GroovyObject> instances ->
                def invalidTypes = instances.any { inst ->
                    inst.class != domainCopy.type
                }
                if (!invalidTypes) {
                    elasticSearchService.unindex(instances)
                } else {
                    throw new IndexException("[${domainCopy.defaultPropertyName}] unindex() method can only be applied on its own type. Please use the elasticSearchService if you want to unindex mixed values.")
                }
            }
            // static unindex( domainInstances ) unindex every instances specified as arguments (ellipsis styled)
            domain.delegateMetaClass.static.unindex << { GroovyObject... instances ->
                delegate.metaClass.invokeStaticMethod(domainCopy.type, 'unindex', instances as Collection<GroovyObject>)
            }
            // unindex() method on domain instance
            domain.delegateMetaClass.unindex << {
                elasticSearchService.unindex(delegate)
            }
        }
    }

    private static String getSearchablePropertyName(GrailsApplication grailsApplication) {
        String searchablePropertyName = grailsApplication.config.getProperty('elasticSearch.searchableProperty.name', String)

        if (searchablePropertyName) {
            return searchablePropertyName
        }

        //Maintain backwards compatibility. Searchable property name may not be defined
        'searchable'
    }
}
