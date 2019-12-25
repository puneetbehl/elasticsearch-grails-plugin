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
package grails.plugins.elasticsearch

import grails.core.GrailsApplication
import grails.core.support.GrailsApplicationAware
import grails.persistence.support.PersistenceContextInterceptor
import grails.plugins.elasticsearch.index.IndexRequestQueue
import grails.plugins.elasticsearch.mapping.SearchableClassMapping
import grails.plugins.elasticsearch.util.GXContentBuilder
import groovy.util.logging.Slf4j
import org.elasticsearch.action.search.SearchRequest
import org.elasticsearch.action.search.SearchResponse
import org.elasticsearch.client.RequestOptions
import org.elasticsearch.client.RestHighLevelClient
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.common.xcontent.DeprecationHandler
import org.elasticsearch.common.xcontent.NamedXContentRegistry
import org.elasticsearch.common.xcontent.XContent
import org.elasticsearch.common.xcontent.XContentParser
import org.elasticsearch.common.xcontent.json.JsonXContent
import org.elasticsearch.index.query.Operator
import org.elasticsearch.index.query.QueryBuilder
import org.elasticsearch.index.query.QueryStringQueryBuilder
import org.elasticsearch.search.SearchHit
import org.elasticsearch.search.SearchHits
import org.elasticsearch.search.SearchModule
import org.elasticsearch.search.aggregations.Aggregation
import org.elasticsearch.search.aggregations.Aggregations
import org.elasticsearch.search.aggregations.AggregatorFactories
import org.elasticsearch.search.aggregations.BaseAggregationBuilder
import org.elasticsearch.search.builder.SearchSourceBuilder
import org.elasticsearch.search.fetch.subphase.highlight.HighlightBuilder
import org.elasticsearch.search.sort.SortBuilder
import org.elasticsearch.search.sort.SortOrder

import static grails.plugins.elasticsearch.util.AbstractQueryBuilderParser.parseInnerQueryBuilder
import static org.elasticsearch.index.query.QueryBuilders.queryStringQuery

@Slf4j
class ElasticSearchService implements GrailsApplicationAware {

    private static final int INDEX_REQUEST = 0
    private static final int DELETE_REQUEST = 1

    GrailsApplication grailsApplication
    ElasticSearchHelper elasticSearchHelper
    def domainInstancesRebuilder
    ElasticSearchContextHolder elasticSearchContextHolder
    IndexRequestQueue indexRequestQueue
    PersistenceContextInterceptor persistenceInterceptor

    static transactional = false

    /**
     * Global search using Query DSL builder.
     *
     * @param params Search parameters
     * @param closure Query closure
     * @param filter The search filter, whether a Closure or a QueryBuilder
     * @param aggregation The search results aggregations, whether a Closure, a BaseAggregationBuilder or a Collection<QueryBuilder>
     */
    ElasticSearchResult search(Map params, Closure query, filter = null, aggregation = null) {
        SearchRequest request = buildSearchRequest(query, filter, aggregation, params)
        search(request, params)
    }

    /**
     * Alias for the search(Map params, Closure query) signature.
     *
     * @param query Query closure
     * @param filter The search filter, whether a Closure or a QueryBuilder
     * @param aggregation The search results aggregations, whether a Closure, a BaseAggregationBuilder or a Collection<QueryBuilder>
     * @param params Search parameters
     * @return A ElasticSearchResult containing the search results
     */
    ElasticSearchResult search(Closure query, filter = null, aggregation = null, Map params = [:]) {
        search(params, query, filter, aggregation)
    }

    /**
     *
     * @param query Query closure
     * @param aggregation The search results aggregations, whether a Closure, a BaseAggregationBuilder or a Collection<QueryBuilder>
     * @param params Search parameters
     * @return A ElasticSearchResult containing the search results
     */
    ElasticSearchResult search(Closure query, aggregation = null, Map params) {
        search(params, query, null, aggregation)
    }

    /**
     * Alias for the search(Map params, QueryBuilder query, Closure filter) signature
     *
     * @param params Search parameters
     * @param query QueryBuilder query
     * @param filter The search filter, whether a Closure or a QueryBuilder
     * @param aggregation The search results aggregations, whether a Closure, a BaseAggregationBuilder or a Collection<QueryBuilder>
     * @return A ElasticSearchResult containing the search results
     */
    ElasticSearchResult search(QueryBuilder query, filter = null, aggregation = null, Map params = [:]) {
        search(params, query, filter, aggregation)
    }

    /**
     * Alias for the search(Map params, QueryBuilder query, Closure filter) signature
     *
     * @param params Search parameters
     * @param query QueryBuilder query
     * @param filter The search filter, whether a Closure or a QueryBuilder
     * @param aggregation The search results aggregations, whether a Closure, a BaseAggregationBuilder or a Collection<QueryBuilder>
     * @return A ElasticSearchResult containing the search results
     */
    ElasticSearchResult search(Map params, QueryBuilder query, filter = null, aggregation = null) {
        SearchRequest request = buildSearchRequest(query, filter, aggregation, params)
        search(request, params)
    }

    /**
     * Global search with a text query.
     *
     * @param query The search query. Will be parsed by the Lucene Query Parser.
     * @param params Search parameters
     * @return A ElasticSearchResult containing the search results
     */
    ElasticSearchResult search(String query, Map params = [:]) {
        SearchRequest request = buildSearchRequest(query, null, null, params)
        search(request, params)
    }

    /**
     * Global search with a text query.
     *
     * @param query The search query. Will be parsed by the Lucene Query Parser.
     * @param params Search parameters
     * @param filter The search filter, whether a Closure or a QueryBuilder
     * @param aggregation The search results aggregations, whether a Closure or a BaseAggregationBuilder
     * @return A ElasticSearchResult containing the search results
     */
    ElasticSearchResult search(String query, filter, aggregation = null, Map params = [:]) {
        SearchRequest request = buildSearchRequest(query, filter, aggregation, params)
        search(request, params)
    }

    /**
     * Returns the number of hits for a peculiar query
     *
     * @param query
     * @param params
     * @return An Integer representing the number of hits for the query
     */
    Integer countHits(String query, Map params = [:]) {
        SearchRequest request = buildCountRequest(query, params)
        count(request, params)
    }

    /**
     * Returns the number of hits for a peculiar query
     *
     * @param query
     * @param params
     * @return An Integer representing the number of hits for the query
     */
    Integer countHits(Map params, Closure query) {
        SearchRequest request = buildCountRequest(query, params)
        count(request, params)
    }

    /**
     * Returns the number of hits for a peculiar query
     *
     * @param query
     * @param params
     * @return An Integer representing the number of hits for the query
     */
    Integer countHits(Closure query, Map params = [:]) {
        countHits(params, query)
    }

    /**
     * Indexes all searchable instances of the specified class.
     * If call without arguments, index ALL searchable instances.
     * Note: The indexRequestQueue is using the bulk API so it is optimized.
     * Todo: should be used along with serializable IDs, but we have to deal with composite IDs beforehand
     *
     * @param options indexing options
     */
    void index(Map options) {
        doBulkRequest(options, INDEX_REQUEST)
    }

    /**
     * An alias for index(class:[MyClass1, MyClass2])
     *
     * @param domainClass List of searchable class
     */
    void index(Class... domainClass) {
        index(class: (domainClass as Collection<Class>))
    }

    /**
     * Indexes domain class instances
     *
     * @param instances A Collection of searchable instances to index
     */
    void index(Collection<GroovyObject> instances) {
        doBulkRequest(instances, INDEX_REQUEST)
    }

    /**
     * Alias for index(Object instances)
     *
     * @param instances
     */
    void index(GroovyObject... instances) {
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
    void unindex(Map options) {
        doBulkRequest(options, DELETE_REQUEST)
    }

    /**
     * An alias for unindex(class:[MyClass1, MyClass2])
     *
     * @param domainClass List of searchable class
     */
    void unindex(Class... domainClass) {
        unindex(class: (domainClass as Collection<Class>))
    }

    /**
     * Unindexes domain class instances
     *
     * @param instances A Collection of searchable instances to index
     */
    void unindex(Collection<GroovyObject> instances) {
        doBulkRequest(instances, DELETE_REQUEST)
    }

    /**
     * Alias for unindex(Object instances)
     *
     * @param instances
     */
    void unindex(GroovyObject... instances) {
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
        List<SearchableClassMapping> mappings = []
        if (clazz) {
            if (clazz instanceof Collection) {
                clazz.each { c ->
                    mappings << elasticSearchContextHolder.getMappingContextByType(c)
                }
            } else {
                mappings << elasticSearchContextHolder.getMappingContextByType(clazz)
            }

        } else {
            mappings = elasticSearchContextHolder.mapping.values() as List
        }
        int max = elasticSearchContextHolder.config.maxBulkRequest ?: 500

        mappings.each { scm ->
            Class<?> domainClass = scm.domainClass.type
            if (scm.root) {
                // how many needs indexing - needed to compute the page size that can be executed concurrently
                int total = domainClass.count()
                log.debug "Found $total instances of $domainClass"

                if (total > 0) {
                    // compute the number of rounds
                    int rounds = Math.ceil(total / max) as int
                    log.debug "Maximum entries allowed in each bulk request is $max, so indexing is split to $rounds iterations"

                    // Couldn't get to work with hibernate due to lost/closed hibernate session errors
                    /*GParsPool.withPool(Runtime.getRuntime().availableProcessors()) {
                        long offset = 0L
                        (1..rounds).each { round ->
                            try {
                                log.debug("Bulk index iteration $round: fetching $max results starting from ${offset}")
                                persistenceInterceptor.init()
                                persistenceInterceptor.setReadOnly()

                                //List<Class<?>> results = domainClass.listOrderById([offset: offset, max: max, order: "asc"])
                                List<Class<?>> results = domainClass.listOrderById([offset: offset, max: max, readOnly: true, sort: 'id', order: "asc"])

                                // set lastId for next run
                                offset = round * max

                                // build blocks of 100s and index them in parallel
                                results.collate(100).eachParallel { List<Map> entries ->
                                    entries.each { def entry ->
                                        if (operationType == INDEX_REQUEST) {
                                            indexRequestQueue.addIndexRequest(entry)
                                            log.debug("Adding the document ${entry.id} to the index request queue")
                                        } else if (operationType == DELETE_REQUEST) {
                                            indexRequestQueue.addDeleteRequest(entry)
                                            log.debug("Adding the document ${entry.id} to the delete request queue")
                                        }
                                        indexRequestQueue.executeRequests()

                                        entry = null
                                    }
                                    entries = null
                                }

                                persistenceInterceptor.flush()
                                persistenceInterceptor.clear()
                                persistenceInterceptor.reconnect()
                                results = null
                                log.info "Request iteration $round out of $rounds finished"
                            } finally {
                                persistenceInterceptor.flush()
                                persistenceInterceptor.clear()
                                persistenceInterceptor.destroy()
                            }
                        }
                    }*/
                    long offset = 0L
                    (1..rounds).each { round ->
                        try {
                            log.debug("Bulk index iteration $round: fetching $max results starting from ${offset}")
                            persistenceInterceptor.init()
                            persistenceInterceptor.setReadOnly()

                            List<Class<?>> results = domainClass.listOrderById([offset: offset, max: max, readOnly: true, sort: 'id', order: "asc"])

                            // set lastId for next run
                            offset = round * max

                            // build blocks of 100s and index them in parallel
                            results.each { def entry ->
                                if (operationType == INDEX_REQUEST) {
                                    indexRequestQueue.addIndexRequest(entry)
                                    log.debug("Adding the document ${entry.id} to the index request queue")
                                } else if (operationType == DELETE_REQUEST) {
                                    indexRequestQueue.addDeleteRequest(entry)
                                    log.debug("Adding the document ${entry.id} to the delete request queue")
                                }
                            }
                            indexRequestQueue.executeRequests()

                            persistenceInterceptor.flush()
                            persistenceInterceptor.clear()
                            persistenceInterceptor.reconnect()
                            results = null
                            log.info "Request iteration $round out of $rounds finished"
                        } finally {
                            persistenceInterceptor.flush()
                            persistenceInterceptor.clear()
                            persistenceInterceptor.destroy()
                        }
                    }
                }


                /*if (operationType == INDEX_REQUEST) {
                     log.debug("Indexing all instances of $domainClass")
                 } else if (operationType == DELETE_REQUEST) {
                     log.debug("Deleting all instances of $domainClass")
                 }

                 // The index is split to avoid out of memory exception
                 def count = domainClass.count() ?: 0
                 log.debug("Found $count instances of $domainClass")

                 int nbRun = Math.ceil(count / max) as int

                 log.debug("Maximum entries allowed in each bulk request is $max, so indexing is split to $nbRun iterations")

                 for (int i = 0; i < nbRun; i++) {

                     int offset = i * max

                     log.debug("Bulk index iteration ${i + 1}: fetching $max results starting from ${offset}")
                     long maxId = offset + max
                     List<Class<?>> results = domainClass.findAllByIdBetween(offset, maxId, [sort: 'id', order: 'asc'])

                     log.debug("Bulk index iteration ${i + 1}: found ${results.size()} results")
                     results.each {
                         if (operationType == INDEX_REQUEST) {
                             indexRequestQueue.addIndexRequest(it)
                             log.debug("Adding the document ${it.id} to the index request queue")
                         } else if (operationType == DELETE_REQUEST) {
                             indexRequestQueue.addDeleteRequest(it)
                             log.debug("Adding the document ${it.id} to the delete request queue")
                         }
                     }
                     indexRequestQueue.executeRequests()

                     log.info("Request iteration ${i + 1} out of $nbRun finished")
                 }*/
            } else {
                log.debug("$domainClass is not a root searchable class and has been ignored.")
            }
        }
    }

    /**
     * Computes a bulk operation on instance level.
     *
     * @param instances The instance related to the operation
     * @param operationType The type of the operation (INDEX_REQUEST, DELETE_REQUEST)
     * @return
     */
    private void doBulkRequest(Collection<GroovyObject> instances, int operationType) {
        instances.each {
            def scm = elasticSearchContextHolder.getMappingContextByType(it.class)
            if (scm && scm.root) {
                if (operationType == INDEX_REQUEST) {
                    indexRequestQueue.addIndexRequest(it)
                } else if (operationType == DELETE_REQUEST) {
                    indexRequestQueue.addDeleteRequest(it)
                }
            } else {
                log.debug("${it.class} is not searchable or not a root searchable class and has been ignored.")
            }
        }
        indexRequestQueue.executeRequests()
    }

    /**
     * Builds a count request
     * @param query
     * @param params
     * @return
     */
    private SearchRequest buildCountRequest(query, Map params) {
        params['size'] = 0
        def request = buildSearchRequest(query, null, null, params)
        request.source().size(0)
        return request
    }

    /**
     * Builds a search request
     *
     * @param params The query parameters
     * @param query The search query, whether a String or a Closure
     * @param filter The search filter, whether a Closure or a QueryBuilder
     * @param aggregation The search results aggregations, whether a Closure, a BaseAggregationBuilder or a Collection<QueryBuilder>
     * @return The SearchRequest instance
     */
    private SearchRequest buildSearchRequest(query, filter, aggregation, Map params) {
        SearchSourceBuilder source = new SearchSourceBuilder()

        log.debug("Build search request with params: ${params}")
        source.from(params.from ? params.from as int : 0)
                .size(params.size ? params.size as int : 60)
                .explain(params.explain ?: true).minScore(params.min_score ?: 0)

        if (params.sort) {
            def sorters = (params.sort instanceof Collection) ? params.sort : [params.sort]

            sorters.each {
                if (it instanceof SortBuilder) {
                    source.sort(it as SortBuilder)
                } else {
                    source.sort(it, SortOrder.valueOf(params.order?.toUpperCase() ?: "ASC"))
                }
            }
        }

        // Handle the query, can either be a closure or a string
        if (query) {
            setQueryInSource(source, query, params)
        }

        if (filter) {
            setFilterInSource(source, filter, params)
        }

        if (aggregation) {
            setAggregationInSource(source, aggregation, params)
        }

        // Handle highlighting
        Closure highlight = params.highlight as Closure
        if (highlight) {
            def highlighter = new HighlightBuilder()
            // params.highlight is expected to provide a Closure.
            highlight.delegate = highlighter
            highlight.resolveStrategy = Closure.DELEGATE_FIRST
            highlight.call()
            source.highlighter highlighter
        }

        source.explain(false)

        SearchRequest request = new SearchRequest()
        String searchType = params.searchType ?:
                elasticSearchContextHolder.config.defaultSearchType ?:
                        'query_then_fetch'
        request.searchType searchType.toLowerCase()
        request.source source

        return request
    }

    SearchSourceBuilder setQueryInSource(SearchSourceBuilder source, String query, Map params = [:]) {
        Operator defaultOperator = params['default_operator'] ?: Operator.AND
        QueryStringQueryBuilder builder = queryStringQuery(query).defaultOperator(defaultOperator)
        if (params.analyzer) {
            builder.analyzer(params.analyzer)
        }
        source.query(builder)
    }

    SearchSourceBuilder setQueryInSource(SearchSourceBuilder source, Closure query, Map params = [:]) {
        def queryBytes = new GXContentBuilder().buildAsBytes(query)
        XContentParser parser = createParser(JsonXContent.jsonXContent, queryBytes)
        def queryBuilder = parseInnerQueryBuilder(parser)

        source.query(queryBuilder)
    }

    SearchSourceBuilder setQueryInSource(SearchSourceBuilder source, QueryBuilder query, Map params = [:]) {
        source.query(query)
    }

    SearchSourceBuilder setAggregationInSource(SearchSourceBuilder source, Closure aggregation, Map params = [:]) {
        def aggregationBytes = new GXContentBuilder().buildAsBytes(aggregation)
        XContentParser parser = createParser(JsonXContent.jsonXContent, aggregationBytes)
        if (parser.currentToken() == null) {
            parser.nextToken();
        }
        def aggregationBuilder = AggregatorFactories.parseAggregators(parser)
        aggregationBuilder.aggregatorFactories.each {
            source.aggregation(it)
        }

        source
    }

    SearchSourceBuilder setAggregationInSource(SearchSourceBuilder source, BaseAggregationBuilder aggregationBuilder, Map params = [:]) {
        source.aggregation(aggregationBuilder)
    }

    SearchSourceBuilder setAggregationInSource(SearchSourceBuilder source, Collection<BaseAggregationBuilder> aggregationBuilder, Map params = [:]) {
        aggregationBuilder.each {
            source.aggregation(it)
        }

        source
    }

    private static SearchModule searchModule = null
    private static NamedXContentRegistry ContentRegistry = null

    private static NamedXContentRegistry getXContentRegistry() {
        if (ContentRegistry == null) {
            searchModule = new SearchModule(Settings.EMPTY, false, Collections.emptyList())
            ContentRegistry = searchModule.namedXContents;
        }
        return ContentRegistry;
    }

    private static XContentParser createParser(XContent xContent, byte[] data) throws IOException {
        return xContent.createParser(getXContentRegistry(), DEPRECATION_HANDLER, data)
    }

    private static final DeprecationHandler DEPRECATION_HANDLER = new DeprecationHandler() {
        @Override
        void usedDeprecatedName(String usedName, String modernName) {
            log.warn("[${usedName}] is deprecated. Use [${modernName}] instead.")
        }

        @Override
        void usedDeprecatedField(String usedName, String replacedWith) {
            log.warn("[${usedName}] is deprecated. Use [${replacedWith}] instead.")
        }
    }

    SearchSourceBuilder setFilterInSource(SearchSourceBuilder source, Closure filter, Map params = [:]) {
        def filterBytes = new GXContentBuilder().buildAsBytes(filter)
        XContentParser parser = createParser(JsonXContent.jsonXContent, filterBytes)
        def filterBuilder = parseInnerQueryBuilder(parser)
        source.postFilter(filterBuilder)
    }

    SearchSourceBuilder setFilterInSource(SearchSourceBuilder source, QueryBuilder filter, Map params = [:]) {
        source.postFilter(filter)
    }

    /**
     * Computes a search request and builds the results
     *
     * @param request The SearchRequest to compute
     * @param params Search parameters
     * @return A Map containing the search results
     */
    def search(SearchRequest request, Map params) {
        resolveIndicesAndTypes(request, params)
        elasticSearchHelper.withElasticSearch { RestHighLevelClient client ->
            log.debug 'Executing search request.'
            log.debug(request.inspect())
            SearchResponse searchResponse = client.search(request, RequestOptions.DEFAULT)
            log.debug 'Completed search request.'
            log.debug(searchResponse.inspect())
            SearchHits searchHits = searchResponse.hits
            ElasticSearchResult result = new ElasticSearchResult()
            result.total = searchHits.totalHits

            log.debug "Search returned ${result.total ?: 0} result(s)."

            // Convert the hits back to their initial type
            result.searchResults = domainInstancesRebuilder.buildResults(searchHits)

            // Extract highlight information.
            // Right now simply give away raw results...
            if (params.highlight) {
                for (SearchHit hit : searchHits) {
                    result.highlight << hit.highlightFields
                }
            }

            log.debug 'Adding score information to results.'

            //Extract score information
            //Records a map from hits of (hit.id, hit.score) returned in 'scores'
            if (params.score) {
                for (SearchHit hit : searchHits) {
                    result.scores[(hit.id)] = hit.score
                }
            }

            if (params.sort) {
                searchHits.each { SearchHit hit ->
                    result.sort[hit.id] = hit.sortValues
                }
            }

            Aggregations aggregations = searchResponse.aggregations
            if (aggregations) {
                Map<String, Aggregation> aggregationsAsMap = aggregations.asMap()
                if (aggregationsAsMap) {
                    result.aggregations = aggregationsAsMap
                }
            }

            result
        }
    }

    /**
     * Computes a count request and returns the results
     *
     * @param request
     * @param params
     * @return Integer The number of hits for the query
     */
    Long count(SearchRequest request, Map params) {
        def result = search(request, params)
        result.total.value
    }
    /**
     * Sets the indices & types properties on SearchRequest & CountRequest
     *
     * @param request
     * @param params
     * @return
     */
    private resolveIndicesAndTypes(request, Map params) {
        assert request instanceof SearchRequest

        // Handle the indices.
        if (params.indices) {
            def indices
            if (params.indices instanceof String) {
                // Shortcut for using 1 index only (not a list of values)
                indices = [params.indices.toLowerCase()]
            } else if (params.indices instanceof Class) {
                // Resolved with the class type
                SearchableClassMapping scm = elasticSearchContextHolder.getMappingContextByType(params.indices)
                indices = [scm.queryingIndex]
            } else if (params.indices instanceof Collection<Class>) {
                indices = params.indices.collect { c ->
                    SearchableClassMapping scm = elasticSearchContextHolder.getMappingContextByType(c)
                    scm.queryingIndex
                }
            }
            request.indices((indices ?: params.indices) as String[])
        } else {
            request.indices("_all")
        }

        /*

        // Handle the types. Each type must reference a Domain class for now, but we may consider to make it more
        // generic in the future to allow POGO/Map/Whatever indexing/searching
        if (params.types) {
            def types
            if (params.types instanceof String) {
                // Shortcut for using 1 type only with a string
                def scm = elasticSearchContextHolder.getMappingContext(params.types)
                if (!scm) {
                    throw new IllegalArgumentException("Unknown object type: ${params.types}")
                }
                types = [scm.elasticTypeName]
            } else if (params.types instanceof Class) {
                // User can also pass a class to determine the type
                def scm = elasticSearchContextHolder.getMappingContextByType(params.types)
                if (!scm) {
                    throw new IllegalArgumentException("Unknown object type: ${params.types}")
                }
                types = [scm.elasticTypeName]
            } else if (params.types instanceof Collection && !params.types.empty) {
                def firstCollectionElement = params.types.first()

                def typeCollectionMethod
                if (firstCollectionElement instanceof Class) {
                    typeCollectionMethod = { type ->
                        elasticSearchContextHolder.getMappingContextByType(type)
                    }
                } else {
                    typeCollectionMethod = { name ->
                        elasticSearchContextHolder.getMappingContext(name)
                    }
                }
                types = params.types.collect { t ->
                    def scm = typeCollectionMethod.call(t)
                    if (!scm) {
                        throw new IllegalArgumentException("Unknown object type: ${params.types}")
                    }
                    scm.elasticTypeName
                }
            }
            request.types(types as String[])
        }
        */
    }
}
