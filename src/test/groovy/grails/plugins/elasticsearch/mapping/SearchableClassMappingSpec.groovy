package grails.plugins.elasticsearch.mapping

import grails.core.GrailsDomainClass
import grails.plugins.elasticsearch.util.IndexNamingUtils
import grails.testing.gorm.DataTest
import grails.testing.spring.AutowiredTest
import org.grails.datastore.gorm.config.GrailsDomainClassMappingContext
import spock.lang.Specification
import test.Photo
import test.upperCase.UpperCase

class SearchableClassMappingSpec extends Specification implements DataTest, AutowiredTest {

    Closure doWithSpring() {
        { ->
            mappingContext GrailsDomainClassMappingContext
            domainReflectionService DomainReflectionService
        }
    }

    DomainReflectionService domainReflectionService

    void setupSpec() {
        mockDomains(Photo, UpperCase)
    }

    def 'indexing and querying index are calculated based on the index name'() {
        given:
            def domainClass = Mock(GrailsDomainClass)
            domainClass.getPackageName() >> packageName
            domainClass.getFullName() >> fullName

        when:
            SearchableClassMapping scm = new SearchableClassMapping(grailsApplication, new DomainEntity(domainReflectionService, domainClass, null), [])

        then:
            scm.indexName == domainClass.fullName.toLowerCase()
            scm.queryingIndex == IndexNamingUtils.queryingIndexFor(fullName.toLowerCase())
            scm.indexingIndex == IndexNamingUtils.indexingIndexFor(fullName.toLowerCase())
            scm.queryingIndex != scm.indexingIndex
            scm.indexName != scm.queryingIndex
            scm.indexName != scm.indexingIndex

        where:
            packageName << ['test.scm', 'com.mapping']
            fullName << ['test.scm.Department', 'com.mapping.Person']
    }

    void testGetIndexName() throws Exception {
        when:
            def domainClass = Mock(GrailsDomainClass)
            domainClass.getFullName() >> 'com.test.Person'
            SearchableClassMapping mapping = new SearchableClassMapping(grailsApplication, new DomainEntity(domainReflectionService, domainClass, null), null)

        then:
            'com.test.person' == mapping.getIndexName()
    }

    void testManuallyConfiguredIndexName() throws Exception {

        when:
            DomainEntity dc = domainReflectionService.getAbstractDomainEntity(Photo.class)
            grailsApplication.config.elasticSearch.index.name = 'index-name'
            SearchableClassMapping mapping = new SearchableClassMapping(grailsApplication, dc, null)

        then:
            'index-name' == mapping.getIndexName()
    }

    void testIndexNameIsLowercaseWhenPackageNameIsLowercase() throws Exception {
        when:
            def domainClass = Mock(GrailsDomainClass)
            domainClass.getFullName() >> 'test.upperCase.Person'
            SearchableClassMapping mapping = new SearchableClassMapping(grailsApplication, new DomainEntity(domainReflectionService, domainClass, null), null)
            String indexName = mapping.getIndexName()

        then:
            'test.uppercase.person' == indexName
    }

    void cleanup() {
        grailsApplication.config.elasticSearch.index.name = null
    }
}
