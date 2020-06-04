package de.cgoit.grails.plugins.elasticsearch

class ElasticsearchBootStrap {

    ElasticSearchBootStrapHelper elasticSearchBootStrapHelper

    def init = { servletContext ->
        elasticSearchBootStrapHelper?.bulkIndexOnStartup()
    }
}
