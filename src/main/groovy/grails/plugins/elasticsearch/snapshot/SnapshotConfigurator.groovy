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
package grails.plugins.elasticsearch.snapshot

import grails.plugins.elasticsearch.ElasticSearchAdminService
import grails.plugins.elasticsearch.ElasticSearchContextHolder
import grails.plugins.elasticsearch.util.ElasticSearchConfigAware
import grails.core.GrailsApplication
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.elasticsearch.cluster.health.ClusterHealthStatus
import org.elasticsearch.cluster.metadata.RepositoryMetaData
import org.elasticsearch.transport.RemoteTransportException

/**
 * Create snapshot repository, install snapshot lifecycle policies.
 */
@Slf4j
@CompileStatic
class SnapshotConfigurator implements ElasticSearchConfigAware {

    ElasticSearchContextHolder elasticSearchContext
    GrailsApplication grailsApplication
    ElasticSearchAdminService es

    /**
     * Init method.
     */
    void configureAndInstallSnapshotSettings() {
        ConfigObject repositoryConfig = snapshotConfig?.repository as ConfigObject
        if (repositoryConfig) {
            createRepository(repositoryConfig)
        }

        List policiesConfig = snapshotConfig?.policies as List
        if (policiesConfig) {
            createSnapshotPolicies(policiesConfig)
        }
    }

    /**
     * Creates the Elasticsearch snapshot repository
     * @param repositoryConfig Repository configuration
     * @throws RemoteTransportException if some other error occurred
     */
    private void createRepository(ConfigObject repositoryConfig) throws RemoteTransportException {
        // Could be blocked on cluster level, thus wait.
        es.waitForClusterStatus(ClusterHealthStatus.YELLOW)

        String repositoryName = repositoryConfig.name as String
        RepositoryMetaData repositoryMetaData = es.getSnapshotRepository(repositoryName)
        if (repositoryMetaData) {
            log.info("Snapshot repository ${repositoryName} exists. MetaData=${repositoryMetaData}")
        } else {
            log.info("Snapshot repository ${repositoryName} doesn't exist. Creating it")
            ConfigObject config = repositoryConfig.config as ConfigObject
            es.createSnapshotRepository(repositoryName, config.type as String, config.settings as Map)
        }
    }

    /**
     * Creates the Elasticsearch snapshot repository
     * @param policiesConfig Snapshot policy configurations
     * @throws RemoteTransportException if some other error occurred
     */
    private void createSnapshotPolicies(List<Map> policiesConfig) throws RemoteTransportException {
        // Could be blocked on cluster level, thus wait.
        es.waitForClusterStatus(ClusterHealthStatus.YELLOW)

        policiesConfig.each { Map policySettings ->
            log.info("Create/Update snapshot policy ${policySettings.id}")
            boolean acknowledged = es.createSnapshotPolicy(policySettings)
            if (!acknowledged) {
                log.warn("Snapshot policy ${policySettings.id} not acknowledged!")
            }
        }

        // Start SLM
        es.startSnapshotLifecycleManagement()
    }

}