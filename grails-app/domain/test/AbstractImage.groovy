package test

abstract class AbstractImage {
    String name
    String type
    int size

    static constraints = {
        name nullable: true
        type nullable: true
    }

    static mapping = {
        autoImport(false)
    }
}
