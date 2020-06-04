package grails.plugins.elasticsearch.mapping

import groovy.transform.CompileStatic

/**
 * Created by @marcos-carceles on 26/01/15.
 */
@CompileStatic
class MappingConflict {

    SearchableClassMapping scm
    Exception exception

    String toString() {
        "Conflict on ${scm.indexName}, due to '${exception.message}'"
    }
}
