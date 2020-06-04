package de.cgoit.grails.plugins.elasticsearch.conversion.marshall

interface Marshaller {
    Object marshall(property)

    /*Object unmarshall()*/
}
