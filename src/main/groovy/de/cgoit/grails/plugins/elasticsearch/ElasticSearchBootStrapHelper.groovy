package de.cgoit.grails.plugins.elasticsearch

import grails.core.GrailsApplication
import de.cgoit.grails.plugins.elasticsearch.util.ElasticSearchConfigAware
import groovy.transform.CompileStatic
import org.slf4j.Logger
import org.slf4j.LoggerFactory

@CompileStatic
class ElasticSearchBootStrapHelper implements ElasticSearchConfigAware {

    private static final Logger LOG = LoggerFactory.getLogger(this)

    GrailsApplication grailsApplication
    ElasticSearchService elasticSearchService
    ElasticSearchAdminService elasticSearchAdminService
    ElasticSearchContextHolder elasticSearchContextHolder

    void bulkIndexOnStartup() {
        def bulkIndexOnStartup = esConfig?.bulkIndexOnStartup
        //Index Content
        if (bulkIndexOnStartup == 'deleted') { //Index lost content due to migration
            LOG.
                    debug "Performing bulk indexing of classes requiring index/mapping migration ${elasticSearchContextHolder.indexesRebuiltOnMigration} on their new version."
            Class[] domainsToReindex = elasticSearchContextHolder.
                    findMappedClassesOnIndices(elasticSearchContextHolder.indexesRebuiltOnMigration) as Class[]
            elasticSearchService.index(domainsToReindex)
        } else if (bulkIndexOnStartup) { //Index all
            LOG.debug 'Performing bulk indexing.'
            elasticSearchService.index(Collections.emptyMap()) // empty map is needed for static compiling
        }
        //Update index aliases where needed
        de.cgoit.grails.plugins.elasticsearch.mapping.MappingMigrationStrategy migrationStrategy =
                migrationConfig?.strategy ? de.cgoit.grails.plugins.elasticsearch.mapping.MappingMigrationStrategy.valueOf(migrationConfig?.strategy as String) :
                de.cgoit.grails.plugins.elasticsearch.mapping.MappingMigrationStrategy.none
        if (migrationStrategy == de.cgoit.grails.plugins.elasticsearch.mapping.MappingMigrationStrategy.alias) {
            elasticSearchContextHolder.indexesRebuiltOnMigration.each { idxName ->
                String indexName = idxName as String
                int latestVersion = elasticSearchAdminService.getLatestVersion(indexName)
                if (!migrationConfig?.disableAliasChange) {
                    elasticSearchAdminService.pointAliasTo de.cgoit.grails.plugins.elasticsearch.util.IndexNamingUtils.queryingIndexFor(indexName), indexName,
                            latestVersion
                }
                elasticSearchAdminService.pointAliasTo de.cgoit.grails.plugins.elasticsearch.util.IndexNamingUtils.indexingIndexFor(indexName), indexName,
                        latestVersion
            }
        }
    }
}
