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
import grails.plugins.elasticsearch.ElasticSearchAdminService
import grails.plugins.elasticsearch.ElasticSearchContextHolder
import grails.plugins.elasticsearch.util.ElasticSearchConfigAware
import groovy.transform.CompileStatic
import org.elasticsearch.ElasticsearchStatusException
import org.elasticsearch.cluster.health.ClusterHealthStatus
import org.elasticsearch.indices.InvalidIndexTemplateException
import org.elasticsearch.transport.RemoteTransportException
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import static grails.plugins.elasticsearch.util.IndexNamingUtils.indexingIndexFor
import static grails.plugins.elasticsearch.util.IndexNamingUtils.queryingIndexFor

/**
 * Build searchable mappings, configure ElasticSearch indexes,
 * build and install ElasticSearch mappings.
 */
@CompileStatic
class SearchableClassMappingConfigurator implements ElasticSearchConfigAware {

    private static final Logger LOG = LoggerFactory.getLogger(this)

    ElasticSearchContextHolder elasticSearchContext
    GrailsApplication grailsApplication
    ElasticSearchAdminService es
    MappingMigrationManager mmm

    DomainReflectionService domainReflectionService

    /**
     * Init method.
     */
    void configureAndInstallMappings() {
        Collection<DomainEntity> domainClasses = domainReflectionService.domainEntities
        Collection<SearchableClassMapping> mappings = mappings(domainClasses)
        installMappings(mappings)
    }

    List<SearchableClassMapping> mappings(Collection<DomainEntity> domainClasses) {
        // TODO: Not able to reflect changes in config if we use instance field config in SearchableDomainClassMapper constructor
        List<SearchableClassMapping> mappings = []
        for (DomainEntity domainClass : domainClasses) {
            SearchableDomainClassMapper mapper = domainReflectionService.createDomainClassMapper(domainClass)
            SearchableClassMapping searchableClassMapping = mapper.buildClassMapping()
            if (searchableClassMapping == null) {
                LOG.debug("Could not build SearchableClassMapping for ${domainClass}")
                continue
            }

            elasticSearchContext.addMappingContext(searchableClassMapping)
            mappings.add(searchableClassMapping)
        }

        // Inject cross-referenced component mappings.
        for (SearchableClassMapping scm : mappings) {
            for (SearchableClassPropertyMapping scpm : scm.getPropertiesMapping()) {
                DomainProperty property = scpm.grailsProperty
                if (scpm.isComponent() && property.isPersistent()) {
                    Class<?> componentType = property.referencedPropertyType
                    scpm.componentPropertyMapping = elasticSearchContext.getMappingContextByType(componentType)
                } else if (scpm.isComponent()) {
                    Class<?> componentType = property.getDomainEntity().getRelatedClassType(scpm.propertyName)
                    scpm.componentPropertyMapping = elasticSearchContext.getMappingContextByType(componentType)
                }
            }
        }

        // Validate all mappings to make sure any cross-references are fine.
        for (SearchableClassMapping scm : mappings) {
            scm.validate(elasticSearchContext)
        }

        return mappings
    }

    /**
     * Resolve the ElasticSearch mapping from the static "searchable" property (closure or boolean) in domain classes
     * @param mappings searchable class mappings to be install.
     */
    void installMappings(Collection<SearchableClassMapping> mappings) {
        Map<String, Object> indexSettings = buildIndexSettings()

        LOG.debug("Index settings are " + indexSettings)

        LOG.debug("Installing mappings...")
        Map<SearchableClassMapping, Map<String, Object>> elasticMappings = buildElasticMappings(mappings)
        LOG.debug "elasticMappings are ${elasticMappings.keySet()}"

        String strategyName = migrationConfig?.strategy as String ?: 'none'
        MappingMigrationStrategy migrationStrategy = MappingMigrationStrategy.valueOf(strategyName)
        def mappingConflicts = []

        Set<String> indices = mappings.findAll { it.isRoot() }.collect {
            it.domainClass.fullName.toLowerCase()
        } as Set<String>

        //Install the mappings for each index all together
        indices.each { String indexName ->

            List<SearchableClassMapping> indexMappings = mappings.findAll {
                it.indexName == indexName && it.isRoot()
            } as List<SearchableClassMapping>
            Map<String, Map> esMappings = indexMappings.collectEntries { [(it.elasticTypeName): elasticMappings[it]] }

            //If the index does not exist we attempt to create all the mappings at once with it
            if (!es.indexExists(indexName)) {
                try {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Creating index [" + indexName + "] => with new mappings:")
                        esMappings.each { String type, Map esMapping ->
                            LOG.debug("\t\tMapping [" + type + "] => " + esMapping)
                        }
                    }
                    createIndexWithMappings(indexName, migrationStrategy, esMappings, indexSettings)
                } catch (RemoteTransportException rte) {
                    LOG.debug(rte.getMessage())
                }
            } else { //We install the mappings one by one
                indexMappings.each { SearchableClassMapping scm ->
                    Map elasticMapping = elasticMappings[scm]
                    // Install mapping
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Installing mapping [" + scm.elasticTypeName + "] => " + elasticMapping)
                    }
                    try {
                        es.createMapping scm.indexName, scm.elasticTypeName, elasticMapping
                    } catch (IllegalArgumentException e) {
                        LOG.warn("Could not install mapping ${scm.indexName}/${scm.elasticTypeName} due to ${e.message}, migrations needed")
                        mappingConflicts << new MappingConflict(scm: scm, exception: e)
                    } catch (InvalidIndexTemplateException e) {
                        LOG.warn("Could not install mapping ${scm.indexName}/${scm.elasticTypeName} due to ${e.message}, migrations needed")
                        mappingConflicts << new MappingConflict(scm: scm, exception: e)
                    } catch (ElasticsearchStatusException e) {
                        LOG.warn("Could not install mapping ${scm.indexName}/${scm.elasticTypeName} due to ${e.message}, migrations needed")
                        mappingConflicts << new MappingConflict(scm: scm, exception: e)
                    } catch (IOException e) {
                        LOG.warn("Could not install mapping ${scm.indexName}/${scm.elasticTypeName} due to ${e.message}, migrations needed")
                        mappingConflicts << new MappingConflict(scm: scm, exception: e)
                    }
                }
            }
            //Create them only if they don't exist so it does not mess with other migrations
            String queryingIndex = queryingIndexFor(indexName)
            String indexingIndex = indexingIndexFor(indexName)
            boolean queryingIndexExists = es.aliasExists(queryingIndex)
            boolean indexingIndexExists = es.aliasExists(indexingIndex)
            if (!queryingIndexExists || !indexingIndexExists) {
                indexName = es.indexNameByAlias(indexName)

                if (!queryingIndexExists) {
                    es.pointAliasTo(queryingIndex, indexName)
                }
                if (!indexingIndexExists) {
                    es.pointAliasTo(indexingIndex, indexName)
                }
            }
        }
        if (mappingConflicts) {
            LOG.info("Applying migrations ...")
            mmm.applyMigrations(migrationStrategy, elasticMappings, mappingConflicts, indexSettings)
        }

        es.waitForClusterStatus(ClusterHealthStatus.YELLOW)
    }

    /**
     * Creates the Elasticsearch index once unblocked and its read and write aliases
     * @param indexName
     * @throws RemoteTransportException if some other error occurred
     */
    private void createIndexWithMappings(String indexName, MappingMigrationStrategy strategy, Map<String, Map> esMappings, Map indexSettings) throws RemoteTransportException {
        // Could be blocked on cluster level, thus wait.
        es.waitForClusterStatus(ClusterHealthStatus.YELLOW)
        if (!es.indexExists(indexName)) {
            LOG.debug("Index ${indexName} does not exists, initiating creation...")
            if (strategy == MappingMigrationStrategy.alias) {
                def nextVersion = es.getNextVersion indexName
                es.createIndex indexName, nextVersion, indexSettings, esMappings
                es.pointAliasTo indexName, indexName, nextVersion
            } else {
                es.createIndex indexName, indexSettings, esMappings
            }
        }
    }

    private Map<String, Object> buildIndexSettings() {
        Map<String, Object> settings = new HashMap<String, Object>()
        settings.put('number_of_replicas', numberOfReplicas())
        // Look for default index settings.
        if (esConfig != null) {
            Map<String, Object> indexDefaults = indexSettings as Map<String, Object>
            LOG.debug('Retrieved index settings')
            if (indexDefaults != null) {
                for (Map.Entry<String, Object> entry : indexDefaults.entrySet()) {
                    String key = entry.key
                    if (key == 'numberOfReplicas') {
                        key = 'number_of_replicas'
                    }
                    if (key == 'analysis') {
                        settings.put('analysis', entry.value)
                    } else {
                        settings.put("index." + key, entry.value)
                    }
                }
            }
        }
        settings
    }

    private static Map<SearchableClassMapping, Map<String, Object>> buildElasticMappings(Collection<SearchableClassMapping> mappings) {
        Map<SearchableClassMapping, Map<String, Object>> elasticMappings = [:]
        for (SearchableClassMapping scm : mappings) {
            if (scm.isRoot()) {
                elasticMappings << [(scm): ElasticSearchMappingFactory.getElasticMapping(scm)]
            }
        }
        elasticMappings
    }

    private int numberOfReplicas() {
        def defaultNumber = indexSettings.numberOfReplicas
        if (!defaultNumber) {
            return 0
        }
        defaultNumber as int
    }
}
