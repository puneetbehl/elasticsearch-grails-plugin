package grails.plugins.elasticsearch

import grails.converters.JSON
import grails.gorm.transactions.Rollback
import grails.testing.mixin.integration.Integration
import org.elasticsearch.action.get.GetRequest
import org.elasticsearch.action.get.GetResponse
import org.elasticsearch.client.RequestOptions
import org.elasticsearch.common.unit.DistanceUnit
import org.elasticsearch.index.query.QueryBuilder
import org.elasticsearch.index.query.QueryBuilders
import org.elasticsearch.join.query.JoinQueryBuilders
import org.elasticsearch.search.aggregations.AggregationBuilders
import org.elasticsearch.search.sort.FieldSortBuilder
import org.elasticsearch.search.sort.SortBuilders
import org.elasticsearch.search.sort.SortOrder
import org.grails.web.json.JSONObject
import spock.lang.Ignore
import spock.lang.Specification
import spock.lang.Unroll
import test.Building
import test.Dates
import test.Department
import test.GeoPoint
import test.Person
import test.Product
import test.Spaceship
import test.Store
import test.custom.id.Toy

import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.OffsetTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

@Integration
@Rollback
class ElasticSearchServiceIntegrationSpec extends Specification implements ElasticSearchSpec {

    static Boolean isSetup = false

    private static final List<Map> EXAMPLE_GEO_BUILDINGS = [
            [lat: 48.13, lon: 11.60, name: '81667'],
            [lat: 48.19, lon: 11.65, name: '85774'],
            [lat: 47.98, lon: 10.18, name: '87700']
    ]

    /**
     * This test class doesn't delete any ElasticSearch indices, because that would also delete the mapping.
     * Be aware of this when indexing new objects.
     */
    def setup() {
        // This is workaround due to issue with Grails3 and springbboot, otherwise we could have added in setupSpec
        if (!isSetup) {
            isSetup = true
            setupData()
        }
    }

    private void setupData() {
        save new Product(productName: 'horst', price: 3.95)
        save new Product(productName: 'hobbit', price: 5.99)
        save new Product(productName: 'best', price: 10.99)
        save new Product(productName: 'high and supreme', price: 45.50)

        EXAMPLE_GEO_BUILDINGS.each {
            GeoPoint geoPoint = save new GeoPoint(lat: it.lat, lon: it.lon)
            save new Building(name: "${it.name}", location: geoPoint)
        }

        /*
        * TODO: Need to identify why test cases are not working after removing this.
        * */
        // elasticSearchService.index()
        // refreshIndices()
    }

    void "test indexing hibernate proxy"() {
        Person abby = new Person(firstName: 'Abby', lastName: 'Reynolds').save(flush: true)
        Spaceship spaceship = new Spaceship(name: 'Arc', captain: abby).save(flush: true)
        clearSession()

        when:
        spaceship = Spaceship.load(spaceship.id)

        then: "spaceship is not null"
        spaceship != null

        when: "index a proxy instance"
        elasticSearchService.index(spaceship)
        refreshIndices()
        ElasticSearchResult search = search(Spaceship, 'arc')

        then:
        search.total.value == 1

        def result = search.searchResults.first()
        result.name == 'Arc'
        result.captain.firstName == 'Abby'
        result.captain.lastName == 'Reynolds'
    }

    void 'Index and un-index a domain object'() {
        given:
            def product = save new Product(productName: 'myTestProduct')

        when:
            search(Product, 'myTestProduct').total.value == 1

        then:
            unindex(product)

        and:
            search(Product, 'myTestProduct').total.value == 0
    }

    void 'Indexing the same object multiple times updates the corresponding ES entry'() {
        given:
            def product = save new Product(productName: 'myTestProduct')

        when:
            index(product)
            refreshIndices()

        then:
            search(Product, 'myTestProduct').total.value == 1

        when:
            product.productName = 'newProductName'
            save product

            index(product)
            refreshIndices()

        then:
            search(Product, 'myTestProduct').total.value == 0

        and:
            def result = search(Product, product.productName)
            result.total.value == 1
            List<Product> searchResults = result.searchResults
            searchResults[0].productName == product.productName
    }

    void 'a json object value should be marshalled and de-marshalled correctly'() {
        given:
            def product = save new Product(
                    productName: 'product with json value',
                    json: new JSONObject("""{ "test": { "details": "blah" } }"""))

            index(product)
            refreshIndices()

        when:
            def result = search(Product, product.productName)

        then:
            result.total.value == 1
            List<Product> searchResults = result.searchResults
            searchResults[0].productName == product.productName
    }

    void 'should marshal the alias field and unmarshal correctly (ignore alias)'() {
        given:
            def location = save new GeoPoint(lat: 53.00, lon: 10.00)
            def building = save new Building(name: 'WatchTower', location: location)

            index(building)
            refreshIndices()

        when:
            def result = search(Building, building.name)

        then:
            result.total.value == 1
            List<Building> searchResults = result.searchResults
            searchResults[0].name == building.name
    }

    void 'a date value should be marshalled and de-marshalled correctly'() {
        given:
        def date = new Date()
        def product = save new Product(productName: 'product with date value', date: date)

        index(product)
        refreshIndices()

        when:
        def result = search(Product, product.productName)

        then:
        result.total.value == 1
        List<Product> searchResults = result.searchResults
        searchResults[0].productName == product.productName
        searchResults[0].date == product.date
    }

    void 'a temporal type value should be marshalled and de-marshalled correctly'() {
        given:
        def zoneId = ZoneId.ofOffset('', ZoneOffset.ofHours(-4))
        def localDate = LocalDate.now()
        def localDateTime = LocalDateTime.now()
        def zonedDateTime = ZonedDateTime.now(zoneId)
        def offsetDateTime = OffsetDateTime.now(zoneId)
        def offsetTime = OffsetTime.now(zoneId)
        def dates = save new Dates(name: 'Object with java.util.time types',
                localDate: localDate,
                localDateTime: localDateTime,
                zonedDateTime: zonedDateTime,
                offsetDateTime: offsetDateTime,
                offsetTime: offsetTime)

        index(dates)
        refreshIndices()

        when:
        def result = search(Dates, dates.name)

        then:
        result.total.value == 1
        List<Dates> searchResults = result.searchResults
        searchResults[0].name == dates.name
        searchResults[0].localDate == dates.localDate
        searchResults[0].localDateTime == dates.localDateTime
        searchResults[0].zonedDateTime == dates.zonedDateTime
        searchResults[0].offsetDateTime == dates.offsetDateTime
        searchResults[0].offsetTime == dates.offsetTime
    }

    void 'a geo point location is marshalled and de-marshalled correctly'() {
        given:
            def location = save new GeoPoint(lat: 53.00, lon: 10.00)
            def building = save new Building(name: 'EvileagueHQ', location: location)

            index(building)
            refreshIndices()

        when:
            def result = search(Building, 'EvileagueHQ')

        then:
            result.total.value == 1
            List<Building> searchResults = result.searchResults
            def resultLocation = searchResults[0].location
            resultLocation.lat == location.lat
            resultLocation.lon == location.lon
    }

    void 'a geo point is mapped correctly'() {
        when:
            GeoPoint location = save new GeoPoint(lat: 53.00, lon: 10.00)
            Building building = save new Building(location: location)

            index(building)
            refreshIndices()

        then:
            Map<String, Object> mapping = getFieldMappingMetaData('test.building', 'building').sourceAsMap
            mapping.properties.location.type == 'geo_point'
    }

    void 'search with geo distance filter'() {
        given: 'a building with a geo point location'
            def geoPoint = save new GeoPoint(lat: 50.1, lon: 13.3)
            def building = save new Building(name: 'Test Product', location: geoPoint)

            elasticSearchService.index(building)
            refreshIndices()

        when: 'a geo distance filter search is performed'
            Map params = [indices: Building, types: Building]
            QueryBuilder query = QueryBuilders.matchAllQuery()
            def location = '50, 13'

            Closure filter = {
                'geo_distance'(
                        'distance': '50km',
                        'location': location)
            }

            def result = elasticSearchService.search(params, query, filter)

        then: 'the building should be found'
            1 == result.total.value
            List<Building> searchResults = result.searchResults
            searchResults[0].id == building.id
    }

    void 'searching with filtered query'() {
        given: 'some products'
            Product.list().each { it.delete() }

            def wurmProduct = save new Product(productName: 'wurm', price: 2.00)
            def hansProduct = save new Product(productName: 'hans', price: 0.5)
            def fooProduct = save new Product(productName: 'foo', price: 5.0)

            index(wurmProduct, hansProduct, fooProduct)
            refreshIndices()

        when: 'searching for a price'
            def result = elasticSearchService.
                    search(QueryBuilders.matchAllQuery(), QueryBuilders.rangeQuery("price").gte(1.99).lte(2.3))

        then: "the result should be product 'wurm'"
            result.total.value == 1
            List<Product> searchResults = result.searchResults
            searchResults[0].productName == wurmProduct.productName
    }

    void 'searching with a FilterBuilder filter and a Closure query'() {
        when: 'searching for a price'
            QueryBuilder filter = QueryBuilders.rangeQuery("price").gte(1.99).lte(2.3)
            def result = elasticSearchService.search(QueryBuilders.matchAllQuery(), filter)

        then: "the result should be product 'wurm'"
            result.total.value == 1
            List<Product> searchResults = result.searchResults
            searchResults[0].productName == "wurm"
    }

    void 'searching with a FilterBuilder filter and a QueryBuilder query'() {
        when: 'searching for a price'
            QueryBuilder filter = QueryBuilders.rangeQuery("price").gte(1.99).lte(2.3)
            def result = elasticSearchService.search(QueryBuilders.matchAllQuery(), filter)

        then: "the result should be product 'wurm'"
            result.total.value == 1
            List<Product> searchResults = result.searchResults
            searchResults[0].productName == "wurm"
    }

    void 'searching with wildcards in query at first position'() {
        given:
            setupData()
            refreshIndices()

        when: 'search with asterisk at first position'
            def result = search(Product, { wildcard(productName: '*st') })

        then: 'the result should contain 2 products'
            result.total.value == 2
            List<Product> searchResults = result.searchResults
            searchResults*.productName.containsAll('best', 'horst')
    }

    void 'searching with wildcards in query at last position'() {
        given:
            setupData()
            refreshIndices()

        when: 'search with asterisk at last position'
            Map params2 = [indices: Product, types: Product]
            def result2 = elasticSearchService.search(
                    {
                        wildcard(productName: 'ho*')
                    }, params2)

        then: 'the result should return 2 products'
            result2.total.value == 2
            List<Product> searchResults2 = result2.searchResults
            searchResults2*.productName.containsAll('horst', 'hobbit')
    }

    void 'searching with wildcards in query in between position'() {
        given:
            setupData()
            refreshIndices()

        when: 'search with asterisk in between position'
            def result = search(Product) {
                wildcard(productName: 's*eme')
            }

        then: 'the result should return 1 product'
            result.total.value == 1
            List<Product> searchResults3 = result.searchResults
            searchResults3[0].productName == 'high and supreme'
    }

    void 'searching for special characters in data pool'() {
        given: 'some products'
            def product = save new Product(productName: 'ästhätik', price: 3.95)

            index(product)
            refreshIndices()

        when: "search for 'a umlaut' "
            def result = elasticSearchService.search({ match(productName: 'ästhätik') })

        then: 'the result should contain 1 product'
            result.total.value == 1
            List<Product> searchResults = result.searchResults
            searchResults[0].productName == product.productName
    }

    @Ignore('Ignored due to the fact that joined documents must be in the same shard. Elasticsearch plugin stores each domain object type in a separate index. So they could never be in the same shard if they are not of the same type.')
    void 'searching for features of the parent element from the actual element'() {
        given: 'parent and child elements'
            def parentParentElement = save new Store(name: 'Eltern-Elternelement', owner: 'Horst')
            def parentElement = save new Department(name: 'Elternelement', numberOfProducts: 4, store: parentParentElement)
            def childElement = save new Product(productName: 'Kindelement', price: 5.00)

            index(parentParentElement, parentElement, childElement)
            refreshIndices()

        when:
            def result = elasticSearchService.search(
                    JoinQueryBuilders.hasParentQuery('store', QueryBuilders.matchQuery('owner', 'Horst'), false),
                    QueryBuilders.matchAllQuery(),
                    [indices: Department, types: Department])

        then:
            !result.searchResults.empty
    }

    void 'Paging and sorting through search results'() {
        given: 'a bunch of products'
            10.times {
                def product = save new Product(productName: "Produkt${it}", price: it)
                index(product)
            }
            refreshIndices()

        when: 'a search is performed'
            def params = [from: 3, size: 2, indices: Product, types: Product, sort: 'productName']
            def query = {
                wildcard(productName: 'produkt*')
            }
            def result = elasticSearchService.search(query, params)

        then: 'the correct result-part is returned'
            result.total.value == 10
            result.searchResults.size() == 2
            result.searchResults*.productName == ['Produkt3', 'Produkt4']
    }

    void 'Multiple sorting through search results'() {
        given: 'a bunch of products'
            def product
            2.times { int i ->
                2.times { int k ->
                    product = new Product(productName: "Yogurt$i", price: k).save(failOnError: true, flush: true)
                    elasticSearchService.index(product)
                }
            }
            refreshIndices()

        when: 'a search is performed'
            def sort1 = new FieldSortBuilder('productName').order(SortOrder.ASC)
            def sort2 = new FieldSortBuilder('price').order(SortOrder.DESC)
            def params = [indices: Product, types: Product, sort: [sort1, sort2]]
            def query = {
                wildcard(productName: 'yogurt*')
            }
            def result = elasticSearchService.search(query, params)

        then: 'the correct result-part is returned'
            result.searchResults.size() == 4
            result.searchResults*.productName == ['Yogurt0', 'Yogurt0', 'Yogurt1', 'Yogurt1']
            result.searchResults*.price == [1, 0, 1, 0]

        when: 'another search is performed'
            sort1 = new FieldSortBuilder('productName').order(SortOrder.DESC)
            sort2 = new FieldSortBuilder('price').order(SortOrder.ASC)
            params = [indices: Product, types: Product, sort: [sort1, sort2]]
            query = {
                wildcard(productName: 'yogurt*')
            }
            result = elasticSearchService.search(query, params)

        then: 'the correct result-part is returned'
            result.total.value == 4
            result.searchResults.size() == 4
            result.searchResults*.productName == ['Yogurt1', 'Yogurt1', 'Yogurt0', 'Yogurt0']
            result.searchResults*.price == [0, 1, 0, 1]
    }

    void 'A search with Uppercase Characters should return appropriate results'() {
        given: 'a product with an uppercase name'
            def product = save new Product(productName: 'Großer Kasten', price: 0.85)

            index(product)
            refreshIndices()

        when: 'a search is performed'
            def result = search(Product) {
                match('productName': 'Großer')
            }

        then: 'the correct result-part is returned'
            result.total.value == 1
            result.searchResults.size() == 1
            result.searchResults*.productName == ['Großer Kasten']
    }

    @Unroll
    void 'a geo distance search finds geo points at varying distances'(String distance, List<String> postalCodesFound) {
        given:
            setupData()
            refreshIndices()

        when: 'a geo distance search is performed'
            Map params = [indices: Building, types: Building]
            QueryBuilder query = QueryBuilders.matchAllQuery()
            def location = [lat: 48.141, lon: 11.57]

            Closure filter = {
                geo_distance(
                        'distance': distance,
                        'location': location)
            }
            def result = elasticSearchService.search(params, query, filter)

        then: 'all geo points in the search radius are found'
            List<Building> searchResults = result.searchResults

            (postalCodesFound.empty && searchResults.empty) ||
                    searchResults.each { it.name in postalCodesFound }

        where:
            distance | postalCodesFound
            '1km'    | []
            '5km'    | ['81667']
            '20km'   | ['81667', '85774']
            '1000km' | ['81667', '85774', '87700']
    }

    void 'A search with lowercase Characters should return appropriate results'() {
        given: 'a product with a lowercase name'
            def product = save new Product(productName: 'KLeiner kasten', price: 0.45)

            index(product)
            refreshIndices()

        when: 'a search is performed'
            def result = search(Product) {
                wildcard('productName': 'klein*')
            }

        then: 'the correct result-part is returned'
            result.total.value == 1
            result.searchResults.size() == 1
            result.searchResults*.productName == ['KLeiner kasten']
    }

    void 'the distances are returned'() {
        given:
            setupData()
            refreshIndices()

            // Building.list().each { it.delete() }

        when: 'a geo distance search is sorted by distance'

            def sortBuilder = SortBuilders.geoDistanceSort('location', 48.141d, 11.57d).
                    unit(DistanceUnit.KILOMETERS).
                    order(SortOrder.ASC)

            Map params = [indices: Building, types: Building, sort: sortBuilder]
            QueryBuilder query = QueryBuilders.matchAllQuery()
            def location = [lat: 48.141, lon: 11.57]

            Closure filter = {
                geo_distance(
                        'distance': '5km',
                        'location': location)
            }
            def result = elasticSearchService.search(params, query, filter)

        and:
            List<Building> searchResults = result.searchResults
            //Avoid double precission issues
            def sortResults = result.sort.(searchResults[0].id).
                    collect { (it as BigDecimal).setScale(4, RoundingMode.HALF_UP) }

        then: 'all geo points in the search radius are found'
            sortResults == [2.5401]
    }

    void 'Component as an inner object'() {
        given:
            def mal = save new Person(firstName: 'Malcolm', lastName: 'Reynolds')
            def spaceship = save new Spaceship(name: 'Serenity', captain: mal)

            index(spaceship)
            refreshIndices()

        when:
            def search = search(Spaceship, 'serenity')

        then:
            search.total.value == 1

            def result = search.searchResults.first()
            result.name == 'Serenity'
            result.captain.firstName == 'Malcolm'
            result.captain.lastName == 'Reynolds'
    }

    void 'Multi_filed creates untouched field'() {
        given:
            def mal = save new Person(firstName: 'J. T.', lastName: 'Esteban')
            def spaceship = save new Spaceship(name: 'USS Grissom', captain: mal)

            index(spaceship)
            refreshIndices()

        when:
            def search = search(Spaceship) {
                bool { must { term("name.untouched": 'USS Grissom') } }
            }

        then:
            search.total.value == 1

            def result = search.searchResults.first()
            result.name == 'USS Grissom'
            result.captain.firstName == 'J. T.'
            result.captain.lastName == 'Esteban'
    }

    void 'Fields creates creates child field'() {
        given:
            def mal = save new Person(firstName: 'Jason', lastName: 'Lambert')
            def spaceship = save new Spaceship(name: 'Intrepid', captain: mal)

            index(spaceship)
            refreshIndices()

        when:
            def search = search(Spaceship) {
                bool { must { term("captain.firstName.raw": 'Jason') } }
            }

        then:
            search.total.value == 1

            def result = search.searchResults.first()
            result.name == 'Intrepid'
            result.captain.firstName == 'Jason'
            result.captain.lastName == 'Lambert'
    }

    void 'The domain type name is returned'() {
        given: 'a product is indexed'
            def product = save new Product(productName: 'My Product', price: 0.45)

            index(product)
            refreshIndices()

        when: 'a search is performed'
            def result = search(Product, 'my product')

        then: 'the correct result-part is returned with '
            result.total.value == 1
            result.searchResults.size() == 1
            result.searchResults*.productName == ['My Product']
            result.searchResults*._domainTypeName == [Product.name]
    }

    void 'dynamicly mapped JSON strings should be searchable'() {
        given: 'A Spaceship with some cool canons'
            def spaceship = new Spaceship(
                    name: 'Spaceball One', captain: new Person(firstName: 'Dark', lastName: 'Helmet').save())
            def data = [engines   : [[name: "Primary", maxSpeed: 'Ludicrous Speed'],
                                     [name: "Secondary", maxSpeed: 'Ridiculous Speed'],
                                     [name: "Tertiary", maxSpeed: 'Light Speed'],
                                     [name: "Main", maxSpeed: 'Sub-Light Speed'],],
                        facilities: ['Shopping Mall', 'Zoo', 'Three-Ring circus']]
            spaceship.shipData = (data as JSON).toString()
            spaceship.save(flush: true, validate: false)

            index(spaceship)
            refreshIndices()

        when: 'a search is executed'
            def search = search(Spaceship) {
                bool { must { term("shipData.engines.name": 'primary') } }
            }

        then: "the json data should be searchable as if it was an actual component of the Spaceship"
            search.total.value == 1
            def result = search.searchResults.first()
            def shipData = JSON.parse(result.shipData)

            result.name == 'Spaceball One'
            shipData.facilities.size() == 3
    }

    void 'Index a domain object with UUID-based id'() {
        given:
            def car = save new Toy(name: 'Car', color: "Red")
            def plane = save new Toy(name: 'Plane', color: "Yellow")

            index(car, plane)
            refreshIndices()

        when:
            def search = search(Toy, 'Yellow')

        then:
            search.total.value == 1
            search.searchResults[0].id == plane.id
    }

    private void createBulkData() {
        1858.times { n ->
            def person = save(new Person(firstName: 'Person', lastName: "McNumbery$n"), false)
            save(new Spaceship(name: "Ship-$n", captain: person), false)
        }
        flushSession()
    }

    void 'bulk test'() {
        given:

        when:
        createBulkData()

        index(Spaceship)
        refreshIndices()

        then:
        findFailures().size() == 0
        elasticSearchService.countHits('Ship\\-') == 1858
    }

    void 'Use an aggregation'() {
        given:
            def jim = save new Product(productName: 'jim', price: 1.99)
            def xlJim = save new Product(productName: 'xl-jim', price: 5.99)

            index(jim, xlJim)
            refreshIndices()

        when:
            def search = elasticSearchService.search(
                    QueryBuilders.matchQuery('productName', 'jim'),
                    null as Closure,
                    AggregationBuilders.max('max_price').field('price'))

        then:
            search.total.value == 2
            search.aggregations.'max_price'.value == 5.99f
    }

    private def findFailures() {
        def domainClass = getDomainClass(Spaceship)
        def failures = []
        def allObjects = Spaceship.list()
        allObjects.each { object ->
            elasticSearchHelper.withElasticSearch { client ->
                GetRequest getRequest = new GetRequest(getIndexName(domainClass), object.id.toString())
                GetResponse getResponse = client.get(getRequest, RequestOptions.DEFAULT)
                if (!getResponse.isExists()) {
                    failures << object
                }
            }
        }
        failures
    }

}
