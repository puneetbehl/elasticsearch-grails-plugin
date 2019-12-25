package test

import org.grails.web.json.JSONObject

class Product {
    String productName
    String description = 'A description of a product'
    Float price = 1.00
    Date date
    JSONObject json

    static searchable = {
        productName fielddata: true
    }

    static constraints = {
        productName blank: false
        description nullable: true
        price nullable: true
        date nullable: true
        json nullable: true
    }

    static mapping = {
        autoImport(false)
        json type: JsonUserType
    }
}
