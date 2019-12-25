package grails.plugins.elasticsearch.mapping

import grails.core.GrailsApplication
import grails.plugins.elasticsearch.ElasticSearchAdminService
import grails.plugins.elasticsearch.ElasticSearchContextHolder
import grails.plugins.elasticsearch.exception.MappingException
import grails.plugins.elasticsearch.util.ElasticSearchConfigAware
import groovy.transform.CompileStatic
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import static grails.plugins.elasticsearch.mapping.MappingMigrationStrategy.*
import static grails.plugins.elasticsearch.util.IndexNamingUtils.indexingIndexFor
import static grails.plugins.elasticsearch.util.IndexNamingUtils.queryingIndexFor

@CompileStatic
class MappingMigrationManager implements ElasticSearchConfigAware {

    ElasticSearchContextHolder elasticSearchContextHolder
    ElasticSearchAdminService es
    GrailsApplication grailsApplication

    private static final Logger LOG = LoggerFactory.getLogger(this)

    def applyMigrations(MappingMigrationStrategy migrationStrategy, Map<SearchableClassMapping, Map> elasticMappings, List<MappingConflict> mappingConflicts, Map indexSettings) {
        switch (migrationStrategy) {
            case delete:
                LOG.error("Delete a Mapping is no longer supported since Elasticsearch 2.0 (see https://www.elastic.co/guide/en/elasticsearch/reference/2.0/indices-delete-mapping.html)." +
                        " To prevent data loss, this strategy has been replaced by 'deleteIndex'")
                throw new MappingException()
            case deleteIndex:
                elasticSearchContextHolder.indexesRebuiltOnMigration = applyDeleteIndexStrategy(elasticMappings, mappingConflicts, indexSettings)
                break
            case alias:
                elasticSearchContextHolder.indexesRebuiltOnMigration = applyAliasStrategy(elasticMappings, mappingConflicts, indexSettings)
                break
            case none:
                LOG.error("Could not install mappings : ${mappingConflicts}. No migration strategy selected.")
                throw new MappingException()
        }
    }

    Set applyDeleteIndexStrategy(Map<SearchableClassMapping, Map> elasticMappings, List<MappingConflict> mappingConflicts, Map indexSettings) {
        Set<String> indices = mappingConflicts.collect { it.scm.indexName } as Set<String>
        indices.each { String indexName ->

            es.deleteIndex indexName

            int nextVersion = es.getNextVersion(indexName)

            rebuildIndexWithMappings(indexName, nextVersion, indexSettings, elasticMappings, true)
        }
        indices
    }

    Set applyAliasStrategy(Map<SearchableClassMapping, Map> elasticMappings, List<MappingConflict> mappingConflicts, Map indexSettings) {

        Set<String> indices = mappingConflicts.collect { it.scm.indexName } as Set<String>

        indices.each { String indexName ->
            LOG.debug("Creating new version and alias for conflicting index ${indexName}")
            boolean conflictOnAlias = es.aliasExists(indexName)
            if (conflictOnAlias || migrationConfig?.aliasReplacesIndex) {

                if (!conflictOnAlias) {
                    es.deleteIndex(indexName)
                }

                int nextVersion = es.getNextVersion(indexName)
                boolean buildQueryingAlias = (!esConfig.bulkIndexOnStartup) && (!conflictOnAlias || !migrationConfig?.disableAliasChange)
                rebuildIndexWithMappings(indexName, nextVersion, indexSettings, elasticMappings, buildQueryingAlias)

            } else {
                throw new MappingException("Could not create alias ${indexName} to solve error installing mappings, index with the same name already exists.")
            }
        }
        indices
    }

    private void rebuildIndexWithMappings(String indexName, int nextVersion, Map indexSettings, Map<SearchableClassMapping, Map> elasticMappings, boolean buildQueryingAlias) {
        Map<String, Map> esMappings = elasticMappings.findAll { SearchableClassMapping scm, Map esMapping ->
            scm.indexName == indexName && scm.isRoot()
        }.collectEntries { SearchableClassMapping scm, Map esMapping ->
            [(scm.elasticTypeName): esMapping]
        } as Map<String, Map>
        es.createIndex indexName, nextVersion, indexSettings, esMappings
        es.waitForIndex indexName, nextVersion //Ensure it exists so later on mappings are created on the right version
        es.pointAliasTo indexName, indexName, nextVersion
        es.pointAliasTo indexingIndexFor(indexName), indexName, nextVersion
        if (buildQueryingAlias) {
            es.pointAliasTo queryingIndexFor(indexName), indexName, nextVersion
        }
    }
}
