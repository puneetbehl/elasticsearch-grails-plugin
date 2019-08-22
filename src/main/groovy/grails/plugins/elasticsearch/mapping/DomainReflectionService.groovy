package grails.plugins.elasticsearch.mapping

import grails.core.GrailsApplication
import grails.core.GrailsDomainClass
import groovy.transform.CompileStatic
import org.grails.core.artefact.DomainClassArtefactHandler
import org.grails.datastore.mapping.model.MappingContext
import org.grails.datastore.mapping.model.PersistentEntity
import org.grails.datastore.mapping.proxy.EntityProxy

@CompileStatic
class DomainReflectionService {

    GrailsApplication grailsApplication

    MappingContext mappingContext

    private final Map<Class<?>, DomainEntity> entityCache = [:]

    private final Map<Class<?>, DomainEntity> abstractEntityCache = [:]

    boolean isDomainEntity(Class<?> clazz) {
        if(clazz in EntityProxy) {
            clazz = clazz.superclass
        }
        DomainClassArtefactHandler.isDomainClass(clazz)
    }

    DomainEntity getDomainEntity(Class<?> clazz) {
        if (!isDomainEntity(clazz)) return null

        entityCache.computeIfAbsent(clazz) {
            def artefact = getDomainClassArtefact(clazz)

            PersistentEntity persistentEntity = mappingContext.getPersistentEntity(clazz.canonicalName)

            artefact ? new DomainEntity(this, artefact, persistentEntity) : null
        }
    }

    Collection<DomainEntity> getDomainEntities() {
        grailsApplication.getArtefacts(DomainClassArtefactHandler.TYPE).toList()
                         .collect { getDomainEntity(((GrailsDomainClass) it).clazz) }
    }

    DomainEntity getAbstractDomainEntity(Class<?> clazz) {
        verifyDomainClass(clazz)

        abstractEntityCache.computeIfAbsent(clazz) {
            new DomainEntity(this, clazz)
        }
    }

    SearchableDomainClassMapper createDomainClassMapper(DomainEntity entity) {
        def config = grailsApplication?.config?.elasticSearch as ConfigObject
        new SearchableDomainClassMapper(grailsApplication, this, entity, config)
    }

    private GrailsDomainClass getDomainClassArtefact(Class<?> clazz) {
        (GrailsDomainClass) grailsApplication.getArtefact(DomainClassArtefactHandler.TYPE, clazz.canonicalName)
    }

    private void verifyDomainClass(Class<?> clazz) {
        if (!isDomainEntity(clazz)) {
            throw new IllegalStateException("Class ${clazz.canonicalName} is not a domain class")
        }
    }

}
