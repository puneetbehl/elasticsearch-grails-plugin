package grails.plugins.elasticsearch.mapping

import grails.plugins.elasticsearch.ElasticSearchContextHolder
import grails.plugins.elasticsearch.ElasticSearchSpec
import grails.testing.mixin.integration.Integration
import spock.lang.Specification
import test.Building
import test.Person
import test.Product

@Integration
class SearchableDomainClassMapperIntegrationSpec extends Specification implements ElasticSearchSpec {

    DomainReflectionService domainReflectionService
    ElasticSearchContextHolder elasticSearchContextHolder

    void setup() {
        resetElasticsearch()
    }

    def 'elasticSearch.defaultExcludedProperties are not mapped'() {
        expect:
            elasticSearchContextHolder.config.defaultExcludedProperties.contains('password')
            Person.searchable instanceof Closure

        when:
            SearchableClassMapping personMapping = elasticSearchContextHolder.getMappingContextByType(Person)

        then:
            !personMapping.propertiesMapping*.grailsProperty*.name.contains('password')

        when:
            SearchableClassMapping incautiousMapping = elasticSearchContextHolder.getMappingContextByType(Person)

        then:
            !incautiousMapping.propertiesMapping*.grailsProperty*.name.containsAll(['firstName', 'lastName', 'password'])

    }

    void 'a domain class with mapping geoPoint: true is mapped as a geo_point'() {
        given: 'a mapper for Building'
            def entity = domainReflectionService.getDomainEntity(Building)
            def mapper = domainReflectionService.createDomainClassMapper(entity)

        when: 'the mapping is built'
            def classMapping = mapper.buildClassMapping()

        then: 'the location is mapped as a geoPoint'
            classMapping.domainClass == entity
            classMapping.elasticTypeName == 'building'
            def locationMapping = classMapping.propertiesMapping.find { it.propertyName == 'location' }
            locationMapping.isGeoPoint()
    }

    void 'the correct mapping is passed to the ES server'() {
        given: 'a class mapping for Building'
            def entity = domainReflectionService.getDomainEntity(Building)
            def mapper = domainReflectionService.createDomainClassMapper(entity)
            def classMapping = mapper.buildClassMapping()

        when:
            def mapping = ElasticSearchMappingFactory.getElasticMapping(classMapping)

        then:
            mapping.building?.properties?.location == [type: 'geo_point']
    }

    void 'a mapping can be built from a domain class'() {
        given: 'a mapper for a domain class'
            def entity = domainReflectionService.getDomainEntity(Product)
            def mapper = domainReflectionService.createDomainClassMapper(entity)

        expect: 'a mapping can be built from this domain class'
            mapper.buildClassMapping()
    }

    void 'a mapping is aliased'() {
        given: 'a mapper for Building'
            def entity = domainReflectionService.getDomainEntity(Building)
            def mapper = domainReflectionService.createDomainClassMapper(entity)

        when: 'the mapping is built'
            def classMapping = mapper.buildClassMapping()

        then: 'the date is aliased'
            classMapping.domainClass == entity
            classMapping.elasticTypeName == 'building'
            def aliasMapping = classMapping.propertiesMapping.find { it.propertyName == 'date' }
            aliasMapping.isAlias()
    }

    void 'can get the mapped alias'() {
        given: 'a mapper for Building'
            def entity = domainReflectionService.getDomainEntity(Building)
            def mapper = domainReflectionService.createDomainClassMapper(entity)

        when: 'the mapping is built'
            def classMapping = mapper.buildClassMapping()

        then: 'the date is aliased'
            classMapping.domainClass == entity
            classMapping.elasticTypeName == 'building'
            def aliasMapping = classMapping.propertiesMapping.find { it.propertyName == 'date' }
            aliasMapping.getAlias() == '@timestamp'
    }

}
