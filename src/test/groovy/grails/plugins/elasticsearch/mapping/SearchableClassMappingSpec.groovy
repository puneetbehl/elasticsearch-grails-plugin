package grails.plugins.elasticsearch.mapping

import grails.plugins.elasticsearch.util.IndexNamingUtils
import grails.testing.gorm.DataTest
import grails.testing.spring.AutowiredTest
import org.grails.datastore.mapping.model.PersistentEntity
import spock.lang.Specification
import test.Photo
import test.all.Post
import test.custom.id.Toy
import test.upperCase.UpperCase

class SearchableClassMappingSpec extends Specification implements DataTest, AutowiredTest {

    Closure doWithSpring() { { ->
            domainReflectionService DomainReflectionService
        } }

    DomainReflectionService domainReflectionService

    void setupSpec() {
        mockDomains(Photo, UpperCase, Post, Toy)
    }

    def 'indexing and querying index are calculated based on the index name'() {
        given:
        PersistentEntity persistentEntity = dataStore.mappingContext.getPersistentEntity(className)

        when:
        SearchableClassMapping scm = new SearchableClassMapping(grailsApplication, new DomainEntity(domainReflectionService, persistentEntity), [])

        then:
        scm.indexName == packageName
        scm.queryingIndex == IndexNamingUtils.queryingIndexFor(packageName)
        scm.indexingIndex == IndexNamingUtils.indexingIndexFor(packageName)
            scm.queryingIndex != scm.indexingIndex
            scm.indexName != scm.queryingIndex
            scm.indexName != scm.indexingIndex

        where:
        className       || packageName
        Post.class.name || "test.all.post"
        Toy.class.name  || "test.custom.id.toy"
    }

    void testGetIndexName() throws Exception {
        when:
        PersistentEntity persistentEntity = dataStore.mappingContext.getPersistentEntity(Photo.class.name)
        SearchableClassMapping mapping = new SearchableClassMapping(grailsApplication, new DomainEntity(domainReflectionService, persistentEntity), null)

        then:
        'test.photo' == mapping.getIndexName()
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
        PersistentEntity persistentEntity = dataStore.mappingContext.getPersistentEntity(UpperCase.class.name)
        SearchableClassMapping mapping = new SearchableClassMapping(grailsApplication, new DomainEntity(domainReflectionService, persistentEntity), null)
            String indexName = mapping.getIndexName()

        then:
        'test.uppercase.uppercase' == indexName
    }

    void cleanup() {
        grailsApplication.config.elasticSearch.index.name = null
    }
}
