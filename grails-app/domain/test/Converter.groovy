package test

class Converter {

    enum Status {

        STATUS1('abc'), STATUS2('123')

        private String value

        Status(String value) {
            this.value = value
        }
    }

    String name
    Status status

    static constraints = {
        name nullable: true
        status nullable: true
    }

    static searchable = {
        status converter: StatusConverter
    }
}