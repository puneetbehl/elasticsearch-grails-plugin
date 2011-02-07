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
package org.grails.plugins.elasticsearch

import org.codehaus.groovy.grails.commons.GrailsApplication
import org.codehaus.groovy.grails.plugins.support.aware.GrailsApplicationAware
import org.elasticsearch.client.Client
import org.elasticsearch.action.search.SearchType
import static org.elasticsearch.client.Requests.searchRequest
import static org.elasticsearch.search.builder.SearchSourceBuilder.searchSource
import static org.elasticsearch.index.query.xcontent.QueryBuilders.queryString
import org.apache.log4j.Logger
import org.elasticsearch.action.search.SearchRequest
import org.elasticsearch.search.builder.SearchSourceBuilder
import org.elasticsearch.search.highlight.HighlightBuilder
import org.elasticsearch.search.SearchHit
import org.grails.plugins.elasticsearch.util.GXContentBuilder

public class ElasticSearchService implements GrailsApplicationAware {
    static LOG = Logger.getLogger("org.grails.plugins.elasticSearch.ElasticSearchService")

    private static final int INDEX_REQUEST = 0
    private static final int DELETE_REQUEST = 1

    GrailsApplication grailsApplication
    def elasticSearchHelper
    def sessionFactory
    def persistenceInterceptor
    def domainInstancesRebuilder
    def elasticSearchContextHolder
    def indexRequestQueue

    boolean transactional = false

    /**
     * Global search using Query DSL builder.
     *
     * @param params Search parameters
     * @param closure Query closure
     * @return search results
     */
    def search(Map params, Closure query) {
        SearchRequest request = buildSearchRequest(query, params)
        return doSearch(request, params)
    }

    /**
     * Alias for the search(Map params, Closure query) signature.
     *
     * @param query Query closure
     * @param params Search parameters
     * @return search results
     */
    def search(Closure query, Map params = [:]) {
        return search(params, query)
    }

    /**
     * Global search with a text query.
     *
     * @param query The search query. Will be parsed by the Lucene Query Parser.
     * @param params Search parameters
     * @return A Map containing the search results
     */
    def search(String query, Map params = [:]) {
        SearchRequest request = buildSearchRequest(query, params)
        return doSearch(request, params)
    }

    /**
     * Indexes all searchable instances of the specified class.
     * If call without arguments, index ALL searchable instances.
     * Note: The indexRequestQueue is using the bulk API so it is optimized.
     * Todo: should be used along with serializable IDs, but we have to deal with composite IDs beforehand
     *
     * @param options indexing options
     */
    public void index(Map options) {
        doBulkRequest(options, INDEX_REQUEST)
    }

    /**
    * An alias for index(class:[MyClass1, MyClass2])
    *
    * @param domainClass List of searchable class
    */
    public void index(Class... domainClass) {
        index(class:(domainClass as Collection<Class>))
    }

    /**
     * Indexes domain class instances
     *
     * @param instances A Collection of searchable instances to index
     */
    public void index(Collection<GroovyObject> instances) {
        doBulkRequest(instances, INDEX_REQUEST)
    }

    /**
     * Alias for index(Object instances)
     *
     * @param instances
     */
    public void index (GroovyObject... instances) {
        index(instances as Collection<GroovyObject>)
    }

    /**
     * Unindexes all searchable instances of the specified class.
     * If call without arguments, unindex ALL searchable instances.
     * Note: The indexRequestQueue is using the bulk API so it is optimized.
     * Todo: should be used along with serializable IDs, but we have to deal with composite IDs beforehand
     *
     * @param options indexing options
     */
    public void unindex(Map options) {
        doBulkRequest(options, DELETE_REQUEST)
    }

    /**
    * An alias for unindex(class:[MyClass1, MyClass2])
    *
    * @param domainClass List of searchable class
    */
    public void unindex(Class... domainClass) {
        unindex(class:(domainClass as Collection<Class>))
    }

    /**
     * Unindexes domain class instances
     *
     * @param instances A Collection of searchable instances to index
     */
    public void unindex(Collection<GroovyObject> instances) {
        doBulkRequest(instances, DELETE_REQUEST)
    }

    /**
     * Alias for unindex(Object instances)
     *
     * @param instances
     */
    public void unindex (GroovyObject... instances) {
        unindex(instances as Collection<GroovyObject>)
    }

    /**
     * Computes a bulk operation on class level.
     *
     * @param options The request options
     * @param operationType The type of the operation (INDEX_REQUEST, DELETE_REQUEST)
     * @return
     */
    private doBulkRequest(Map options, int operationType) {
        def clazz = options.class
        def mappings = []
        if(clazz) {
            if(clazz instanceof Collection) {
                clazz.each { c ->
                    mappings << elasticSearchContextHolder.getMappingContextByType(c)
                }
            } else {
                mappings << elasticSearchContextHolder.getMappingContextByType(clazz)
            }

        } else {
            mappings = elasticSearchContextHolder.mapping.values()
        }

        mappings.each { scm ->
            if(scm.root) {
                if (operationType == INDEX_REQUEST) {
                    LOG.debug("Indexing all instances of ${scm.domainClass}")
                } else if (operationType == DELETE_REQUEST) {
                    LOG.debug("Deleting all instances of ${scm.domainClass}")
                }
                scm.domainClass.metaClass.invokeStaticMethod(scm.domainClass.clazz, "getAll", null).each {
                    if(operationType == INDEX_REQUEST) {
                        indexRequestQueue.addIndexRequest(it)
                    } else if (operationType == DELETE_REQUEST) {
                        indexRequestQueue.addDeleteRequest(it)
                    }
                }
            } else {
                LOG.debug("${scm.domainClass.clazz} is not a root searchable class and has been ignored.")
            }
        }
        indexRequestQueue.executeRequests()
    }

    /**
     * Computes a bulk operation on instance level.
     *
     * @param instances The instance related to the operation
     * @param operationType The type of the operation (INDEX_REQUEST, DELETE_REQUEST)
     * @return
     */
    private doBulkRequest(Collection<GroovyObject> instances, int operationType) {
        instances.each {
            def scm = elasticSearchContextHolder.getMappingContextByType(it.class)
            if(scm && scm.root) {
                if (operationType == INDEX_REQUEST) {
                    indexRequestQueue.addIndexRequest(it)
                } else if (operationType == DELETE_REQUEST) {
                    indexRequestQueue.addDeleteRequest(it)
                }
            } else {
                LOG.debug("${it.class} is not searchable or not a root searchable class and has been ignored.")
            }
        }
        indexRequestQueue.executeRequests()
    }

    /**
     * Build a search request
     *
     * @param params The query parameters
     * @param query The search query, whether a String or a Closure
     * @return The SearchRequest instance
     */
    private SearchRequest buildSearchRequest(query, Map params) {
        SearchRequest request = new SearchRequest()
        request.searchType SearchType.DFS_QUERY_THEN_FETCH

        // Handle the indices.
        // TODO: if the user specify a DomainClass or a Collection of DomainClass, we're able to determine
        // automatically both the indices and the types. Eg: elasticSearchService.search("query", for:[Tweet, User])
        if (params.indices) {
            if(params.indices instanceof String){
                // Shortcut for using 1 index only (not a list of values)
                request.indices([params.indices] as String[])
            } else {
                // Here we consider that params.indices is a Collection or a Array
                request.indices(params.indices as String[])
            }
        }

        // Handle the types. Each type must reference a Domain class for now, but we may consider to make it more
        // generic in the future to allow POGO/Map/Whatever indexing/searching
        if (params.types) {
            if(params.types instanceof String) {
                // Shortcut for using 1 type only with a string
                def scm = elasticSearchContextHolder.getMappingContext(params.types)
                request.types([scm.elasticTypeName] as String[])
            } else if(params.types instanceof Collection<String>) {
                def types = params.types.collect { t ->
                    def scm = elasticSearchContextHolder.getMappingContext(t)
                    scm.elasticTypeName
                }
                request.types(types as String[])
            } else if (params.types instanceof Class) {
                // User can also pass a class to determine the type
                def scm = elasticSearchContextHolder.getMappingContextByType(params.types)
                request.types([scm.elasticTypeName] as String[])
            } else if (params.types instanceof Collection<Class>) {
                def types = params.types.collect { t ->
                    def scm = elasticSearchContextHolder.getMappingContextByType(t)
                    scm.elasticTypeName
                }
                request.types(types as String[])
            }

        }
        SearchSourceBuilder source = new SearchSourceBuilder()

        source.from(params.from ? params.from as int : 0)
        source.size(params.size ? params.size as int : 60)
        source.explain(params.explain ?: true)

        // Handle the query, can either be a closure or a string
        if(query instanceof Closure){
            source.query(new GXContentBuilder().buildAsBytes(query))
        } else {
            source.query(queryString(query))
        }

        // Handle highlighting
        if (params.highlight) {
            def highlighter = new HighlightBuilder()
            // params.highlight is expected to provide a Closure.
            def highlightBuilder = params.highlight
            highlightBuilder.delegate = highlighter
            highlightBuilder.resolveStrategy = Closure.DELEGATE_FIRST
            highlightBuilder.call()
            source.highlight highlighter
        }
        request.source source

        return request
    }

    /**
     * Compute a search request and build the results
     *
     * @param request The SearchRequest to compute
     * @param params Search parameters
     * @return A Map containing the search results
     */
    private doSearch(SearchRequest request, Map params) {
        elasticSearchHelper.withElasticSearch { Client client ->
            def response = client.search(request).actionGet()
            def searchHits = response.hits()
            def result = [:]
            result.total = searchHits.totalHits()

            LOG.debug "Search returned ${result.total ?: 0} result(s)."

            // Convert the hits back to their initial type
            result.searchResults = domainInstancesRebuilder.buildResults(searchHits)

            // Extract highlight information.
            // Right now simply give away raw results...
            if (params.highlight) {
                def highlightResults = []
                for(SearchHit hit : searchHits) {
                    highlightResults << hit.highlightFields
                }
                result.highlight = highlightResults
            }

            return result
        }
    }
}