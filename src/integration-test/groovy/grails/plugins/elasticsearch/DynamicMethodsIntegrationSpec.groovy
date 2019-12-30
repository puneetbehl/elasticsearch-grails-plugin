package grails.plugins.elasticsearch

import grails.gorm.transactions.Rollback
import grails.testing.mixin.integration.Integration
import org.elasticsearch.index.query.Operator
import org.elasticsearch.index.query.QueryBuilder
import org.elasticsearch.index.query.QueryBuilders
import org.elasticsearch.search.aggregations.AggregationBuilder
import org.elasticsearch.search.aggregations.AggregationBuilders
import org.elasticsearch.search.aggregations.bucket.filter.FiltersAggregator
import spock.lang.Specification
import test.Photo


@Integration
@Rollback
class DynamicMethodsIntegrationSpec extends Specification implements ElasticSearchSpec {

    def setup() {
        save new Photo(name: 'Captain Kirk', type: 'png', size: 100, url: 'http://www.nicenicejpg.com/100')
        save new Photo(name: 'Captain Picard', type: 'png', size: 200, url: 'http://www.nicenicejpg.com/200')
        save new Photo(name: 'Captain Sisko', type: 'png', size: 300, url: 'http://www.nicenicejpg.com/300')
        save new Photo(name: 'Captain Janeway', type: 'jpg', size: 400, url: 'http://www.nicenicejpg.com/400')
        save new Photo(name: 'Captain Archer', type: 'jpg', size: 500, url: 'http://www.nicenicejpg.com/500')
    }

    def cleanup() {
        Photo.list().each { it.delete() }
    }

    void 'can search using Dynamic Methods'() {
        given:
            refreshIndices()

        expect:
            search(Photo, 'Captain').total.value == 5

        when:
            def results = Photo.search {
                match(name: 'Captain')
            }

        then:
            results.total.value == 5
            results.searchResults.every { it.name =~ /Captain/ }
    }

    void 'can search and filter using Dynamic Methods'() {
        given:
        refreshIndices()

        expect:
        search(Photo, 'Captain').total.value == 5

        when:
        def results = Photo.search({
            match(name: 'Captain')
        }, {
            match {
                "url"(query: "http://www.nicenicejpg.com/100", operator: "and")
            }
        })

        then:
        results.total.value == 1
        results.searchResults[0].name == 'Captain Kirk'
    }

    void 'can search, filter and aggregate using Dynamic Methods'() {
        given:
        refreshIndices()

        expect:
        search(Photo, 'Captain').total.value == 5

        when:
        def results = Photo.search({
            match(name: 'Captain')
        }, {
            match {
                "url"(query: "http://www.nicenicejpg.com/100", operator: "and")
            }
        }, {
            "types" {
                filters {
                    "filters" {
                        "jpg" { match(type: 'jpg') }
                        "png" { match(type: 'png') }
                    }
                }
            }

            "names" {
                  terms(field: 'name')
            }

            "avg_size" {
                avg(field: 'size')
            }
        })

        then:
        results.total.value == 1
        results.searchResults[0].name == 'Captain Kirk'

        results.aggregations.size() == 3
        results.aggregations['types'].buckets.size() == 2
        results.aggregations['types'].buckets[0].key == 'jpg'
        results.aggregations['types'].buckets[0].docCount == 2
        results.aggregations['types'].buckets[1].key == 'png'
        results.aggregations['types'].buckets[1].docCount == 3

        results.aggregations['names'].buckets.size() == 6

        results.aggregations['avg_size'].value == 300
    }

    void 'can search using a QueryBuilder and Dynamic Methods'() {
        given:
        refreshIndices()

        expect:
        search(Photo, 'Captain').total.value == 5

        when:
        QueryBuilder query = QueryBuilders.matchQuery('url', 'http://www.nicenicejpg.com/100').operator(Operator.AND)
        def results = Photo.search(query)

        then:
        results.total.value == 1
        results.searchResults[0].name == 'Captain Kirk'
    }

    void 'can search using a QueryBuilder and aggregations and Dynamic Methods'() {
        given:
        refreshIndices()

        expect:
        search(Photo, 'Captain').total.value == 5

        when:
        QueryBuilder query = QueryBuilders.matchQuery('url', 'http://www.nicenicejpg.com/100').operator(Operator.AND)
        def results = Photo.search(query,
                null as Closure,
                {
                    "types" {
                        filters {
                            "filters" {
                                "jpg" { match(type: 'jpg') }
                                "png" { match(type: 'png') }
                            }
                        }
                    }

                    "names" {
                        terms(field: 'name')
                    }

                    "avg_size" {
                        avg(field: 'size')
                    }
                })

        then:
        results.total.value == 1
        results.searchResults[0].name == 'Captain Kirk'

        results.aggregations.size() == 3
        results.aggregations['types'].buckets.size() == 2
        results.aggregations['types'].buckets[0].key == 'jpg'
        results.aggregations['types'].buckets[0].docCount == 0
        results.aggregations['types'].buckets[1].key == 'png'
        results.aggregations['types'].buckets[1].docCount == 1

        results.aggregations['names'].buckets.size() == 2

        results.aggregations['avg_size'].value == 100
    }

    void 'can search using a QueryBuilder, a filter and Dynamic Methods'() {
        given:
        refreshIndices()

        expect:
        search(Photo, 'Captain').total.value == 5

        when:
        QueryBuilder query = QueryBuilders.matchQuery('name', 'Captain')
        def results = Photo.search(query,
                {
                    match {
                        "url"(query: "http://www.nicenicejpg.com/100", operator: "and")
                    }
                })

        then:
        results.total.value == 1
        results.searchResults[0].name == 'Captain Kirk'
    }

    void 'can search using a QueryBuilder, a filter, aggregations and Dynamic Methods'() {
        given:
        refreshIndices()

        expect:
        search(Photo, 'Captain').total.value == 5

        when:
        QueryBuilder query = QueryBuilders.matchQuery('name', 'Captain')
        def results = Photo.search(query,
                {
                    match {
                        "url"(query: "http://www.nicenicejpg.com/100", operator: "and")
                    }
                },
                {
                    "types" {
                        filters {
                            "filters" {
                                "jpg" { match(type: 'jpg') }
                                "png" { match(type: 'png') }
                            }
                        }
                    }

                    "names" {
                        terms(field: 'name')
                    }

                    "avg_size" {
                        avg(field: 'size')
                    }
                }
        )

        then:
        results.total.value == 1
        results.searchResults[0].name == 'Captain Kirk'

        results.aggregations.size() == 3
        results.aggregations['types'].buckets.size() == 2
        results.aggregations['types'].buckets[0].key == 'jpg'
        results.aggregations['types'].buckets[0].docCount == 2
        results.aggregations['types'].buckets[1].key == 'png'
        results.aggregations['types'].buckets[1].docCount == 3

        results.aggregations['names'].buckets.size() == 6

        results.aggregations['avg_size'].value == 300
    }

    void 'can search using a QueryBuilder, a FilterBuilder and Dynamic Methods'() {
        given:
        refreshIndices()

        expect:
        search(Photo, 'Captain').total.value == 5

        when:
        QueryBuilder query = QueryBuilders.matchAllQuery()
        QueryBuilder filter = QueryBuilders.matchQuery('url', 'http://www.nicenicejpg.com/100').operator(Operator.AND)
        def results = Photo.search(query, filter)

        then:
        results.total.value == 1
        results.searchResults[0].name == 'Captain Kirk'
    }

    void 'can search using a QueryBuilder, a FilterBuilder, a AggregationBuilder and Dynamic Methods'() {
        given:
        refreshIndices()

        expect:
        search(Photo, 'Captain').total.value == 5

        when:
        QueryBuilder query = QueryBuilders.matchAllQuery()
        QueryBuilder filter = QueryBuilders.matchQuery('url', 'http://www.nicenicejpg.com/100').operator(Operator.AND)
        def aggregations = []
        aggregations << AggregationBuilders.filters('types',
                new FiltersAggregator.KeyedFilter('jpg', QueryBuilders.matchQuery('type', 'jpg')),
                new FiltersAggregator.KeyedFilter('png', QueryBuilders.matchQuery('type', 'png'))
        )
        aggregations << AggregationBuilders.terms('names').field('name')
        aggregations << AggregationBuilders.avg('avg_size').field('size')

        def results = Photo.search(query, filter, aggregations)

        then:
        results.total.value == 1
        results.searchResults[0].name == 'Captain Kirk'

        results.aggregations.size() == 3
        results.aggregations['types'].buckets.size() == 2
        results.aggregations['types'].buckets[0].key == 'jpg'
        results.aggregations['types'].buckets[0].docCount == 2
        results.aggregations['types'].buckets[1].key == 'png'
        results.aggregations['types'].buckets[1].docCount == 3

        results.aggregations['names'].buckets.size() == 6

        results.aggregations['avg_size'].value == 300
    }

    void 'can search and filter using Dynamic Methods and a QueryBuilder'() {
        given:
        refreshIndices()

        expect:
        search(Photo, 'Captain').total.value == 5

        when:
        QueryBuilder filter = QueryBuilders.matchQuery('url', 'http://www.nicenicejpg.com/100').operator(Operator.AND)
        def results = Photo.search({
            match(name: 'Captain')
        }, filter)

        then:
        results.total.value == 1
        results.searchResults[0].name == 'Captain Kirk'
    }

    void 'can search and filter using Dynamic Methods, a QueryBuilder and a AggregationBuilder'() {
        given:
        refreshIndices()

        expect:
        search(Photo, 'Captain').total.value == 5

        when:
        QueryBuilder filter = QueryBuilders.matchQuery('url', 'http://www.nicenicejpg.com/100').operator(Operator.AND)
        def aggregations = []
        aggregations << AggregationBuilders.filters('types',
                new FiltersAggregator.KeyedFilter('jpg', QueryBuilders.matchQuery('type', 'jpg')),
                new FiltersAggregator.KeyedFilter('png', QueryBuilders.matchQuery('type', 'png'))
        )
        aggregations << AggregationBuilders.terms('names').field('name')
        aggregations << AggregationBuilders.avg('avg_size').field('size')
        def results = Photo.search({
            match(name: 'Captain')
        }, filter, aggregations)

        then:
        results.total.value == 1
        results.searchResults[0].name == 'Captain Kirk'

        results.aggregations.size() == 3
        results.aggregations['types'].buckets.size() == 2
        results.aggregations['types'].buckets[0].key == 'jpg'
        results.aggregations['types'].buckets[0].docCount == 2
        results.aggregations['types'].buckets[1].key == 'png'
        results.aggregations['types'].buckets[1].docCount == 3

        results.aggregations['names'].buckets.size() == 6

        results.aggregations['avg_size'].value == 300
    }

    void 'can search using a QueryBuilder, a FilterBuilder, an aggregation closure and Dynamic Methods'() {
        given:
        refreshIndices()

        expect:
        search(Photo, 'Captain').total.value == 5

        when:
        QueryBuilder query = QueryBuilders.matchAllQuery()
        QueryBuilder filter = QueryBuilders.matchQuery('url', 'http://www.nicenicejpg.com/100').operator(Operator.AND)
        def results = Photo.search(query, filter, {
            "types" {
                filters {
                    "filters" {
                        "jpg" { match(type: 'jpg') }
                        "png" { match(type: 'png') }
                    }
                }
            }

            "names" {
                terms(field: 'name')
            }

            "avg_size" {
                avg(field: 'size')
            }
        })

        then:
        results.total.value == 1
        results.searchResults[0].name == 'Captain Kirk'

        results.aggregations.size() == 3
        results.aggregations['types'].buckets.size() == 2
        results.aggregations['types'].buckets[0].key == 'jpg'
        results.aggregations['types'].buckets[0].docCount == 2
        results.aggregations['types'].buckets[1].key == 'png'
        results.aggregations['types'].buckets[1].docCount == 3

        results.aggregations['names'].buckets.size() == 6

        results.aggregations['avg_size'].value == 300
    }

}
