package grails.plugins.elasticsearch

import grails.plugins.elasticsearch.index.IndexRequestQueue
import grails.plugins.elasticsearch.mapping.SearchableClassMapping
import groovy.json.JsonSlurper
import org.apache.http.util.EntityUtils
import org.elasticsearch.action.admin.indices.alias.IndicesAliasesRequest
import org.elasticsearch.action.admin.indices.alias.get.GetAliasesRequest
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest
import org.elasticsearch.action.admin.indices.get.GetIndexRequest
import org.elasticsearch.action.admin.indices.mapping.get.GetMappingsRequest
import org.elasticsearch.action.admin.indices.mapping.put.PutMappingRequest
import org.elasticsearch.action.admin.indices.refresh.RefreshRequest
import org.elasticsearch.action.support.broadcast.BroadcastResponse
import org.elasticsearch.client.*
import org.elasticsearch.cluster.health.ClusterHealthStatus
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.common.xcontent.XContentHelper
import org.elasticsearch.common.xcontent.XContentType
import org.elasticsearch.index.query.MatchAllQueryBuilder
import org.elasticsearch.index.reindex.DeleteByQueryRequest
import org.elasticsearch.rest.RestStatus
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.regex.Matcher

class ElasticSearchAdminService {

    static transactional = false

    static final Logger LOG = LoggerFactory.getLogger(this)

    ElasticSearchHelper elasticSearchHelper
    ElasticSearchContextHolder elasticSearchContextHolder
    IndexRequestQueue indexRequestQueue
    def jsonSlurper = new JsonSlurper()

    private static final WAIT_FOR_INDEX_MAX_RETRIES = 10
    private static final WAIT_FOR_INDEX_SLEEP_INTERVAL = 100

    /**
     * Explicitly refresh one or more index, making all operations performed since the last refresh available for search
     * This method will also flush all pending request in the indexRequestQueue and will wait for their completion.
     * @param indices The indices to refresh. If null, will refresh ALL indices.
     */
    void refresh(Collection<String> indices = null) {
        // Flush any pending operation from the index queue
        indexRequestQueue.executeRequests()

        // Refresh ES
        elasticSearchHelper.withElasticSearch { RestHighLevelClient client ->
            RefreshRequest request = new RefreshRequest(indices as String[])
            BroadcastResponse response = client.indices().refresh(request, RequestOptions.DEFAULT)

            if (response.getFailedShards() > 0) {
                LOG.info 'Refresh failure'
            } else {
                LOG.info "Refreshed ${indices ?: 'all'} indices"
            }
        }
    }

    /**
     * Explicitly refresh one or more index, making all operations performed since the last refresh available for search
     * This method will also flush all pending request in the indexRequestQueue and will wait for their completion.
     * @param indices The indices to refresh. If null, will refresh ALL indices.
     */
    void refresh(String... indices) {
        refresh(indices as Collection<String>)
    }

    /**
     * Explicitly refresh ALL index, making all operations performed since the last refresh available for search
     * This method will also flush all pending request in the indexRequestQueue and will wait for their completion.
     * @param searchableClasses The indices represented by the specified searchable classes to refresh. If null, will refresh ALL indices.
     */
    void refresh(Class... searchableClasses) {
        def toRefresh = []

        // Retrieve indices to refresh
        searchableClasses.each {
            SearchableClassMapping scm = elasticSearchContextHolder.getMappingContextByType(it)
            if (scm) {
                toRefresh << scm.queryingIndex
                toRefresh << scm.indexingIndex
            }
        }

        refresh(toRefresh.unique())
    }

    /**
     * Delete one or more index and all its data.
     * @param indices The indices to delete. If null, will delete ALL indices.
     */
    void deleteIndex(Collection<String> indices = null) {
        elasticSearchHelper.withElasticSearch { RestHighLevelClient client ->
            if (!indices) {
                client.indices().delete(new DeleteIndexRequest('_all'), RequestOptions.DEFAULT)
                LOG.info "Deleted all indices"
            } else {
                indices.each {
                    client.indices().delete(new DeleteIndexRequest(it), RequestOptions.DEFAULT)
                }
                LOG.info "Deleted indices $indices"
            }
        }
    }

    /**
     * Deletes all documents from one.
     * @param aliasIndexName The alias index name from which to delete the documents.
     */
    void deleteAllDocumentsFromIndex(String aliasIndexName) {
        String indexName = indexNameByAlias(aliasIndexName)
        elasticSearchHelper.withElasticSearch { RestHighLevelClient client ->
            DeleteByQueryRequest request = new DeleteByQueryRequest(indexName)
            request.setQuery(new MatchAllQueryBuilder())
            client.deleteByQuery(request, RequestOptions.DEFAULT)
            LOG.info "Deleted all documents from $aliasIndexName"
        }
    }

    /**
     * Delete one or more index and all its data.
     * @param indices The indices to delete. If null, will delete ALL indices.
     */
    void deleteIndex(String... indices) {
        deleteIndex(indices as Collection<String>)
    }

    /**
     * Delete one or more index and all its data.
     * @param indices The indices to delete in the form of searchable class(es).
     */
    void deleteIndex(Class... searchableClasses) {
        def toDelete = []

        // Retrieve indices to delete
        searchableClasses.each {
            SearchableClassMapping scm = elasticSearchContextHolder.getMappingContextByType(it)
            if (scm) {
                toDelete << scm.indexName
            }
        }
        // We do not trigger the deleteIndex with an empty list as it would delete ALL indices.
        // If toDelete is empty, it might be because of a misuse of a Class the user thought to be a searchable class
        if (!toDelete.isEmpty()) {
            deleteIndex(toDelete.unique())
        }
    }

    /**
     * Creates mappings on a type
     * @param index The index where the mapping is being created
     * @param type The type where the mapping is created
     * @param elasticMapping The mapping definition
     */
    void createMapping(String index, String type, Map elasticMapping) {
        LOG.info("Creating Elasticsearch mapping for ${index} and type ${type} ...")
        elasticSearchHelper.withElasticSearch { RestHighLevelClient client ->
            client.indices().putMapping(
                    new PutMappingRequest(index)
                            .type(type)
                            .source(elasticMapping),
                    RequestOptions.DEFAULT
            )
        }
    }

    /**
     * Check whether a mapping exists
     * @param index The name of the index to check on
     * @param type The type which mapping is being checked
     * @return true if the mapping exists
     */
    boolean mappingExists(String index, String type) {
        elasticSearchHelper.withElasticSearch { RestHighLevelClient client ->
            !client.indices().getMapping(new GetMappingsRequest().indices(index).types(type), RequestOptions.DEFAULT).mappings.empty
        }
    }

    /**
     * Deletes a version of an index
     * @param index The name of the index
     * @param version the version number, if provided <index>_v<version> will be used
     */
    void deleteIndex(String index, Integer version = null) {
        index = versionIndex index, version
        LOG.info("Deleting  Elasticsearch index ${index} ...")
        elasticSearchHelper.withElasticSearch { RestHighLevelClient client ->
            client.indices().delete(new DeleteIndexRequest(index), RequestOptions.DEFAULT)
        }
    }

    /**
     * Creates a new index
     * @param index The name of the index
     * @param settings The index settings (ie. number of shards)
     */
    void createIndex(String index, Map settings = null, Map<String, Map> esMappings = [:]) {
        LOG.debug "Creating index ${index} ..."

        elasticSearchHelper.withElasticSearch { RestHighLevelClient client ->
            CreateIndexRequest request = new CreateIndexRequest(index)
            if (settings) {
                Map flattenedMap = flattenMap(settings)

                Settings.Builder settingsBuilder = Settings.builder()
                flattenedMap.each {
                    if (it.value instanceof List) {
                        it.value.eachWithIndex { entry, int i ->
                            settingsBuilder.put("${it.key.toString()}.${i}", entry.toString())
                        }

                    } else {
                        settingsBuilder.put(it.key.toString(), it.value.toString())
                    }
                }
                request.settings(settingsBuilder)

            }
            esMappings.each { String type, Map elasticMapping ->
                request.mapping(type, elasticMapping)
            }

            client.indices().create(request, RequestOptions.DEFAULT)
        }
    }

    /**
     * Creates a new index
     * @param index The name of the index
     * @param version the version number, if provided <index>_v<version> will be used
     * @param settings The index settings (ie. number of shards)
     */
    void createIndex(String index, Integer version, Map settings = null, Map<String, Map> esMappings = [:]) {
        index = versionIndex(index, version)
        createIndex(index, settings, esMappings)
    }

    /**
     * Checks whether the index exists
     * @param index The name of the index
     * @param version the version number, if provided <index>_v<version> will be used
     * @return true , if the index exists
     */
    boolean indexExists(String index, Integer version = null) {
        index = versionIndex(index, version)
        elasticSearchHelper.withElasticSearch { RestHighLevelClient client ->
            GetIndexRequest request = new GetIndexRequest()
            request.indices(index)
            request.humanReadable(true)
            client.indices().exists(request, RequestOptions.DEFAULT)
        }
    }

    /**
     * Waits for the specified version of the index to exist
     * @param index The name of the index
     * @param version the version number
     */
    void waitForIndex(String index, int version) {
        int retries = WAIT_FOR_INDEX_MAX_RETRIES
        while (getLatestVersion(index) < version && retries--) {
            LOG.debug("Index ${versionIndex(index, version)} not found, sleeping for ${WAIT_FOR_INDEX_SLEEP_INTERVAL}...")
            Thread.sleep(WAIT_FOR_INDEX_SLEEP_INTERVAL)
        }
    }

    /**
     * Returns the name of the index pointed by an alias
     * @param alias The alias to be checked
     * @return the name of the index
     */
    String indexPointedBy(String alias) {
        elasticSearchHelper.withElasticSearch { RestHighLevelClient client ->
            GetAliasesResponse aliasesResponse = client.indices().getAlias(new GetAliasesRequest(alias), RequestOptions.DEFAULT)
            if (aliasesResponse.error) {
                LOG.debug(aliasesResponse.error)
            }
            aliasesResponse.aliases?.find {
                alias in it.value*.alias()
            }?.key
        }
    }

    /**
     * Deletes an alias pointing to an index
     * @param alias The name of the alias
     */
    void deleteAlias(String alias) {
        elasticSearchHelper.withElasticSearch { RestHighLevelClient client ->
            IndicesAliasesRequest request = new IndicesAliasesRequest();
            IndicesAliasesRequest.AliasActions removeAction =
                    new IndicesAliasesRequest.AliasActions(IndicesAliasesRequest.AliasActions.Type.REMOVE)
                            .index(indexPointedBy(alias))
                            .alias(alias)
            request.addAliasAction(removeAction)
            client.indices().updateAliases(request, RequestOptions.DEFAULT)
        }
    }

    /**
     * Makes an alias point to a new index, removing the relationship with a previous index, if any
     * @param alias the alias to be created/modified
     * @param index the index to be pointed to
     * @param version the version number, if provided <index>_v<version> will be used
     */
    void pointAliasTo(String alias, String index, Integer version = null) {
        index = versionIndex(index, version)
        LOG.debug "Creating alias ${alias}, pointing to index ${index} ..."
        String oldIndex = indexPointedBy(alias)
        elasticSearchHelper.withElasticSearch { RestHighLevelClient client ->
            if (oldIndex && oldIndex != index) {
                LOG.debug "Index used to point to ${oldIndex}, removing ..."
                new IndicesAliasesRequest.AliasActions(IndicesAliasesRequest.AliasActions.Type.REMOVE)
                        .index(oldIndex)
                        .alias(alias)
            }
            IndicesAliasesRequest request = new IndicesAliasesRequest()
            IndicesAliasesRequest.AliasActions aliasAction =
                    new IndicesAliasesRequest.AliasActions(IndicesAliasesRequest.AliasActions.Type.ADD)
                            .index(index)
                            .alias(alias)
            request.addAliasAction(aliasAction)
            LOG.debug "Create alias -> index: ${index}; alias: ${alias}"
            client.indices().updateAliases(request, RequestOptions.DEFAULT)
        }
    }

    /**
     * Checks whether an alias exists
     * @param alias the name of the alias
     * @return true if the alias exists
     */
    boolean aliasExists(String alias) {
        elasticSearchHelper.withElasticSearch { RestHighLevelClient client ->
            client.indices().existsAlias(new GetAliasesRequest(alias), RequestOptions.DEFAULT)
        }
    }

    /**
     * Returns the index name by the given alias
     * @param alias the name of the alias
     * @return i if the index name if exists
     */
    String indexNameByAlias(String alias) {
        elasticSearchHelper.withElasticSearch { RestHighLevelClient client ->
            GetAliasesResponse aliasesResponse = client.indices().getAlias(new GetAliasesRequest(alias), RequestOptions.DEFAULT)
            if (aliasesResponse.status() == RestStatus.NOT_FOUND) {
                alias
            } else {
                aliasesResponse.getAliases()?.entrySet()?.iterator()?.next()?.getKey()
            }


        }
    }

    /**
     * Builds an index name based on a base index and a version number
     * @param index
     * @param version
     * @return <index>   _v<version> if version is provided, <index> otherwise
     */
    String versionIndex(String index, Integer version = null) {
        version == null ? index : index + "_v${version}"
    }

    /**
     * Returns all the indices
     * @param prefix the prefix
     * @return a Set of index names
     */
    Set<String> getIndices() {
        elasticSearchHelper.withElasticSearch { RestHighLevelClient client ->
            Request request = new Request("GET", "/_aliases")
            Response response = client.lowLevelClient.performRequest(request)
            String jsonResponse = EntityUtils.toString(response.getEntity())
            jsonSlurper.parseText(jsonResponse).collect { it.key } as Set<String>
        }
    }

    /**
     * Returns all the indices starting with a prefix
     * @param prefix the prefix
     * @return a Set of index names
     */
    Set<String> getIndices(String prefix) {
        Set indices = getIndices()
        if (prefix) {
            indices = indices.findAll {
                it =~ /^${prefix}/
            }
        }
        indices
    }

    /**
     * The current version of the index
     * @param index
     * @return the current version if any exists, -1 otherwise
     */
    int getLatestVersion(String index) {
        def versions = getIndices(index).collect {
            Matcher m = (it =~ /^${index}_v(\d+)$/)
            m ? m[0][1] as Integer : -1
        }.sort()
        versions ? versions.last() : -1
    }

    /**
     * The next available version for an index
     * @param index the index name
     * @return an integer representing the next version to be used for this index (ie. 10 if the latest is <index>_v<9>)
     */
    int getNextVersion(String index) {
        getLatestVersion(index) + 1
    }

    /**
     * Waits for the cluster to be on Yellow status
     */
    void waitForClusterStatus(ClusterHealthStatus status = ClusterHealthStatus.YELLOW) {
        elasticSearchHelper.withElasticSearch { RestHighLevelClient client ->

            Request request = new Request("GET", "/_cluster/health")
            request.addParameter("wait_for_status", "yellow")
            Response response = client.getLowLevelClient().performRequest(request)

            ClusterHealthStatus healthStatus

            response.getEntity().getContent().withStream { is ->
                Map<String, Object> map = XContentHelper.convertToMap(XContentType.JSON.xContent(), is, true)
                healthStatus = ClusterHealthStatus.fromString((String) map.get("status"))
            }
            LOG.debug("Cluster status: ${healthStatus}")
        }
    }

    //From http://groovy.329449.n5.nabble.com/Flatten-Map-using-closure-td364360.html
    Map flattenMap(map) {
        [:].putAll(map.entrySet().flatten {
            it.value instanceof Map ? it.value.collect { k, v -> new MapEntry(it.key + '.' + k, v) } : it
        })
    }

}
