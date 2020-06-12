package grails.plugins.elasticsearch.exception

class MappingException extends Exception {

    MappingException() {
        super()
    }

    MappingException(String message) {
        super(message)
    }
}
