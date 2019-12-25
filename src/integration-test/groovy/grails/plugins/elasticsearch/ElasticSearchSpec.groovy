package grails.plugins.elasticsearch

import grails.core.GrailsApplication
import grails.plugins.elasticsearch.mapping.DomainEntity
import grails.plugins.elasticsearch.mapping.SearchableClassMappingConfigurator
import grails.util.GrailsNameUtils
import org.elasticsearch.action.admin.indices.get.GetIndexRequest
import org.elasticsearch.action.admin.indices.get.GetIndexResponse
import org.elasticsearch.action.search.SearchRequest
import org.elasticsearch.client.RequestOptions
import org.elasticsearch.client.RestHighLevelClient
import org.elasticsearch.cluster.metadata.MappingMetaData
import org.elasticsearch.common.collect.ImmutableOpenMap
import org.elasticsearch.index.query.QueryBuilder
import org.grails.datastore.gorm.GormEntity
import org.hibernate.SessionFactory
import org.springframework.beans.factory.annotation.Autowired

trait ElasticSearchSpec {

    @Autowired
    GrailsApplication grailsApplication

    @Autowired
    SessionFactory sessionFactory

    @Autowired
    ElasticSearchService elasticSearchService

    @Autowired
    ElasticSearchAdminService elasticSearchAdminService

    @Autowired
    ElasticSearchHelper elasticSearchHelper

    @Autowired
    SearchableClassMappingConfigurator searchableClassMappingConfigurator

    void resetElasticsearch() {
        deleteIndices()
        searchableClassMappingConfigurator.configureAndInstallMappings()
    }

    static <T> T save(GormEntity<T> object, boolean flush = true) {
        object.save(flush: flush, failOnError: true)
    }

    ElasticSearchResult search(Class<?> clazz, String query) {
        elasticSearchService.search(query, [indices: clazz, types: clazz])
    }

    ElasticSearchResult search(Class<?> clazz, Closure query) {
        elasticSearchService.search(query, [indices: clazz, types: clazz])
    }

    ElasticSearchResult search(String query, Map params = [:]) {
        elasticSearchService.search(query, params)
    }

    ElasticSearchResult search(SearchRequest request, Map params) {
        elasticSearchService.search(request, params)
    }

    ElasticSearchResult search(Class<?> clazz, QueryBuilder queryBuilder) {
        elasticSearchService.search([indices: clazz, types: clazz], queryBuilder)
    }

    void clearSession() {
        sessionFactory.currentSession.clear()
    }

    void flushSession() {
        sessionFactory.currentSession.flush()
    }

    void refreshIndices() {
        elasticSearchAdminService.refresh()
    }

    void refreshIndex(Collection<String> indices) {
        elasticSearchAdminService.refresh(indices)
    }

    void index(GroovyObject... instances) {
        elasticSearchService.index(instances as Collection<GroovyObject>)
    }

    void index(Class... domainClass) {
        elasticSearchService.index(domainClass)
    }

    void unindex(GroovyObject... instances) {
        elasticSearchService.unindex(instances as Collection<GroovyObject>)
    }

    void unindex(Class... domainClass) {
        elasticSearchService.unindex(domainClass)
    }

    void refreshIndex(Class... searchableClasses) {
        elasticSearchAdminService.refresh(searchableClasses)
    }

    void deleteIndices() {
        elasticSearchAdminService.deleteIndex()
        elasticSearchAdminService.refresh()
    }

    String getIndexName(DomainEntity domainClass) {
        String name = grailsApplication.config.getProperty("elasticSearch.index.name", String) ?: domainClass.fullName
        if (!name) {
            name = domainClass.defaultPropertyName
        }
        name.toLowerCase()
    }

    String getTypeName(DomainEntity domainClass) {
        GrailsNameUtils.getPropertyName(domainClass.type)
    }

    DomainEntity getDomainClass(Class<?> clazz) {
        elasticSearchService.elasticSearchContextHolder.getMappingContextByType(clazz).domainClass
    }

    MappingMetaData getFieldMappingMetaData(String indexName, String documentType) {
        if (elasticSearchAdminService.aliasExists(indexName)) {
            indexName = elasticSearchAdminService.indexPointedBy(indexName)
        }
        elasticSearchHelper.withElasticSearch { RestHighLevelClient client ->
            GetIndexRequest request = new GetIndexRequest().indices(indexName)
            GetIndexResponse getIndexResponse = client.indices().get(request, RequestOptions.DEFAULT)
            ImmutableOpenMap<String, MappingMetaData> indexMappings = getIndexResponse.mappings.get(indexName)
            indexMappings.get(documentType)
        }
    }

}
