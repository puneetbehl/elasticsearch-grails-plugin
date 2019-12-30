package grails.plugins.elasticsearch.mapping

import groovy.transform.CompileStatic

@CompileStatic
enum MappingMigrationStrategy {
    none,
    delete,
    deleteIndex,
    alias
}
