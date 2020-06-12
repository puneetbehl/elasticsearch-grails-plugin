package grails.plugins.elasticsearch.util

import grails.core.GrailsApplication
import groovy.transform.CompileStatic

@CompileStatic
trait ElasticSearchConfigAware {

    abstract GrailsApplication getGrailsApplication()

    ConfigObject getEsConfig() {
        grailsApplication?.config?.elasticSearch as ConfigObject
    }

    ConfigObject getIndexSettings() {
        (esConfig?.index as ConfigObject)?.settings as ConfigObject
    }

    ConfigObject getMigrationConfig() {
        esConfig?.migration as ConfigObject
    }

    ConfigObject getSnapshotConfig() {
        esConfig?.snapshots as ConfigObject
    }
}
