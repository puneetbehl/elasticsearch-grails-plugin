package test

import java.beans.PropertyEditor
import java.beans.PropertyEditorSupport
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.OffsetTime
import java.time.ZonedDateTime

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