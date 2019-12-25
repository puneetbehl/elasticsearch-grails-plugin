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
package grails.plugins.elasticsearch.index

import grails.plugins.elasticsearch.ElasticSearchContextHolder
import grails.plugins.elasticsearch.conversion.JSONDomainFactory
import grails.plugins.elasticsearch.exception.IndexException
import grails.plugins.elasticsearch.mapping.SearchableClassMapping
import grails.plugins.elasticsearch.unwrap.DomainClassUnWrapperChain
import org.codehaus.groovy.runtime.InvokerHelper
import org.elasticsearch.action.ActionListener
import org.elasticsearch.action.bulk.*
import org.elasticsearch.action.delete.DeleteRequest
import org.elasticsearch.action.index.IndexRequest
import org.elasticsearch.client.RequestOptions
import org.elasticsearch.client.RestHighLevelClient
import org.elasticsearch.common.unit.ByteSizeUnit
import org.elasticsearch.common.unit.ByteSizeValue
import org.elasticsearch.common.unit.TimeValue
import org.elasticsearch.common.xcontent.XContentBuilder
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.util.Assert

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.function.BiConsumer

/**
 * Holds objects to be indexed.
 * <br/>
 * It looks like we need to keep object references in memory until they indexed properly.
 * If indexing fails, all failed objects are retried. Still no support for max number of retries (todo)
 * NOTE: if cluster state is RED, everything will probably fail and keep retrying forever.
 * NOTE: This is shared class, so need to be thread-safe.
 */
class IndexRequestQueue {

    private static final Logger LOG = LoggerFactory.getLogger(this)

    private JSONDomainFactory jsonDomainFactory
    private ElasticSearchContextHolder elasticSearchContextHolder
    private RestHighLevelClient elasticSearchClient

    /**
     * A map containing the pending index requests.
     */
    private Map<IndexEntityKey, Object> indexRequests = new ConcurrentHashMap<>()

    /**
     * A set containing the pending delete requests.
     */
    private Set<IndexEntityKey> deleteRequests = []

    //private ConcurrentLinkedDeque<OperationBatch> operationBatch = new ConcurrentLinkedDeque<OperationBatch>()

    void setJsonDomainFactory(JSONDomainFactory jsonDomainFactory) {
        this.jsonDomainFactory = jsonDomainFactory
    }

    void setElasticSearchContextHolder(ElasticSearchContextHolder elasticSearchContextHolder) {
        this.elasticSearchContextHolder = elasticSearchContextHolder
    }

    void setElasticSearchClient(RestHighLevelClient elasticSearchClient) {
        this.elasticSearchClient = elasticSearchClient
    }

    void addIndexRequest(instance) {
        addIndexRequest(instance, null)
    }

    void addIndexRequest(instance, Serializable id) {
        def unwrappedInstance = domainClassUnWrapperChain.unwrap(instance)

        IndexEntityKey key = id == null ? indexEntityKeyFromInstance(unwrappedInstance)
                                        : new IndexEntityKey(id.toString(), unwrappedInstance.getClass())

        synchronized (this) {
            indexRequests.put(key, unwrappedInstance)
        }
    }

    void addDeleteRequest(instance) {
        def unwrappedInstance = domainClassUnWrapperChain.unwrap(instance)

        IndexEntityKey key = indexEntityKeyFromInstance(unwrappedInstance)

        synchronized (this) {
            deleteRequests.add(key)
        }
    }

    IndexEntityKey indexEntityKeyFromInstance(instance) {
        def clazz = instance.getClass()
        SearchableClassMapping scm = elasticSearchContextHolder.getMappingContextByType(clazz)
        Assert.notNull(scm, "Class $clazz is not a searchable domain class.")
        def id = (InvokerHelper.invokeMethod(instance, 'getId', null)).toString()
        new IndexEntityKey(id, clazz)
    }

    XContentBuilder toJSON(instance) {
        try {
            return jsonDomainFactory.buildJSON(instance)
        } catch (Throwable t) {
            throw new IndexException("Failed to marshall domain instance [$instance]", t)
        }
    }

    /**
     * Execute pending requests and clear both index & delete pending queues.
     *
     * @return Returns an OperationBatch instance which is a listener to the last executed bulk operation. Returns NULL
     *         if there were no operations done on the method call.
     */
    void executeRequests() {
        Map<IndexEntityKey, Object> toIndex = [:]
        Set<IndexEntityKey> toDelete = []

        // Copy existing queue to ensure we are interfering with incoming requests.
        synchronized (this) {
            toIndex.putAll(indexRequests)
            toDelete.addAll(deleteRequests)
            indexRequests.clear()
            deleteRequests.clear()
        }

        // If there are domain instances that are both in the index requests & delete requests list,
        // they are directly deleted.
        toIndex.keySet().removeAll(toDelete)

        // If there is nothing in the queues, just stop here
        if (toIndex.isEmpty() && toDelete.empty) {
            return
        }

        try {
            BulkProcessor.Listener listener = new BulkProcessor.Listener() {
                int count = 0

                @Override
                void beforeBulk(long l, BulkRequest bulkRequest1) {
                    count = count + bulkRequest1.numberOfActions()
                    LOG.debug("Executed " + count + " so far")
                }

                @Override
                void afterBulk(long l, BulkRequest bulkRequest1, BulkResponse bulkResponse) {
                    if (bulkResponse.hasFailures()) {
                        for (BulkItemResponse bulkItemResponse : bulkResponse) {
                            if (bulkItemResponse.isFailed()) {
                                BulkItemResponse.Failure failure = bulkItemResponse.getFailure()
                                LOG.error("Error " + failure.toString())
                            }
                        }
                    }
                }

                @Override
                void afterBulk(long l, BulkRequest bulkRequest1, Throwable throwable) {
                    LOG.error("Big errors " + throwable.toString())
                }
            }

            BiConsumer<BulkRequest, ActionListener<BulkResponse>> bulkConsumer = ({ request, bulkListener ->
                elasticSearchClient.bulkAsync(request, RequestOptions.DEFAULT, bulkListener)
            } as BiConsumer)
            BulkProcessor bulkProcessor = BulkProcessor.builder(bulkConsumer, listener)
                    .setBulkActions(10000)
                    .setBulkSize(new ByteSizeValue(5, ByteSizeUnit.MB))
                    .setFlushInterval(TimeValue.timeValueSeconds(5))
                    .setConcurrentRequests(1)
                    .setBackoffPolicy(
                    BackoffPolicy.exponentialBackoff(TimeValue.timeValueMillis(100), 3))
                    .build()

            toIndex.each { key, value ->
                SearchableClassMapping scm = elasticSearchContextHolder.getMappingContextByType(key.clazz)

                try {
                    XContentBuilder json = toJSON(value)
                    bulkProcessor.add(new IndexRequest(scm.indexingIndex).id(key.id).source(json))

                    if (LOG.isDebugEnabled()) {
                        try {
                            LOG.debug("Indexing $key.clazz (index: $scm.indexingIndex , type: $scm.elasticTypeName) of id $key.id and source ${json.getOutputStream().toString()}")
                        } catch (IOException e) {
                        }
                    }
                } catch (Exception e) {
                    LOG.error("Error Indexing $key.clazz (index: $scm.indexingIndex , type: $scm.elasticTypeName) of id $key.id", e)
                }
            }

            // Execute delete requests
            toDelete.each {
                SearchableClassMapping scm = elasticSearchContextHolder.getMappingContextByType(it.clazz)
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Deleting object from index $scm.indexingIndex and type $scm.elasticTypeName and ID $it.id")
                }
                bulkProcessor.add(new DeleteRequest(scm.indexingIndex, scm.elasticTypeName, it.id))
            }

            bulkProcessor.awaitClose(30L, TimeUnit.SECONDS)
        } catch (Exception e) {
            throw new IndexException("Failed to index/delete ${bulkProcessor.numberOfActions()}", e)
        }
    }
}
