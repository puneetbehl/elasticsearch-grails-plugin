package grails.plugins.elasticsearch

import groovy.transform.stc.ClosureParams
import groovy.transform.stc.SimpleType
import org.elasticsearch.client.RestHighLevelClient

class ElasticSearchHelper {

    RestHighLevelClient elasticSearchClient

    def <R> R withElasticSearch(@ClosureParams(value = SimpleType, options = 'org.elasticsearch.client.RestHighLevelClient') Closure<R> callable) {
        callable.call(elasticSearchClient)
    }

}
