package grails.plugins.elasticsearch.conversion.marshall

interface Marshaller {
    Object marshall(property)

    /*Object unmarshall()*/
}
