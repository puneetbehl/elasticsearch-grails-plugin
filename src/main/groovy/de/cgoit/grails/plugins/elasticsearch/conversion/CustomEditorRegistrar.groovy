package de.cgoit.grails.plugins.elasticsearch.conversion

import grails.core.GrailsApplication
import de.cgoit.grails.plugins.elasticsearch.ElasticSearchContextHolder
import de.cgoit.grails.plugins.elasticsearch.conversion.binders.JSONDateBinder
import org.springframework.beans.PropertyEditorRegistrar
import org.springframework.beans.PropertyEditorRegistry

class CustomEditorRegistrar implements PropertyEditorRegistrar {
    ElasticSearchContextHolder elasticSearchContextHolder
    GrailsApplication grailsApplication

    void registerCustomEditors(PropertyEditorRegistry reg) {
        elasticSearchContextHolder = grailsApplication.mainContext.getBean('elasticSearchContextHolder')
        reg.registerCustomEditor(Date, new JSONDateBinder(elasticSearchContextHolder.config.date.formats as List))
    }
}
