package grails.plugins.elasticsearch

import grails.core.GrailsApplication
import grails.util.GrailsNameUtils
import org.grails.datastore.gorm.GormEntity
import grails.plugins.elasticsearch.mapping.DomainEntity
import grails.plugins.elasticsearch.mapping.SearchableClassMappingConfigurator

import org.springframework.beans.factory.annotation.Autowired

import org.hibernate.SessionFactory

import org.elasticsearch.action.admin.cluster.state.ClusterStateRequestBuilder
import org.elasticsearch.action.search.SearchRequest
import org.elasticsearch.client.AdminClient
import org.elasticsearch.client.ClusterAdminClient
import org.elasticsearch.cluster.ClusterState
import org.elasticsearch.cluster.metadata.IndexMetaData
import org.elasticsearch.cluster.metadata.MappingMetaData
import org.elasticsearch.index.query.QueryBuilder


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
        String name = grailsApplication.config.getProperty("elasticSearch.index.name", String) ?: domainClass.packageName
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

    MappingMetaData getFieldMappingMetaData(String indexName, String typeName) {
        if (elasticSearchAdminService.aliasExists(indexName)) {
            indexName = elasticSearchAdminService.indexPointedBy(indexName)
        }
        AdminClient admin = elasticSearchHelper.elasticSearchClient.admin()
        ClusterAdminClient cluster = admin.cluster()
        ClusterStateRequestBuilder indices = cluster.prepareState().setIndices(indexName)
        ClusterState clusterState = indices.execute().actionGet().state
        IndexMetaData indexMetaData = clusterState.metaData.index(indexName)
        return indexMetaData.mapping(typeName)
    }

}
