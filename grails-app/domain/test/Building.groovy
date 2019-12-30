package test

import java.time.LocalDate
import java.time.LocalDateTime

class Building {

    String name
    Date date = new Date()
    LocalDate localDate = LocalDate.now()
    LocalDateTime localDateTime = LocalDateTime.now()
    GeoPoint location

    static constraints = {
        name(nullable: true)
    }

    static searchable = {
        location geoPoint: true, component: true
        date alias: "@timestamp"
    }
}
