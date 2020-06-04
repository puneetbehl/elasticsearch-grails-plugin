package de.cgoit.grails.plugins.elasticsearch.mapping

import grails.core.GrailsApplication
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

    static boolean isDomainEntity(Class<?> clazz) {
        if (clazz in EntityProxy) {
            clazz = clazz.superclass
        }
        DomainClassArtefactHandler.isDomainClass(clazz)
    }

    DomainEntity getDomainEntity(Class<?> clazz) {
        if (!isDomainEntity(clazz)) {
            return null
        }

        entityCache.computeIfAbsent(clazz) {
            PersistentEntity persistentEntity = mappingContext.getPersistentEntity(clazz.canonicalName)
            persistentEntity ? new DomainEntity(this, persistentEntity) : null
        }
    }

    Collection<DomainEntity> getDomainEntities() {
        mappingContext.getPersistentEntities().collect { new DomainEntity(this, it) }
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

    private static void verifyDomainClass(Class<?> clazz) {
        if (!isDomainEntity(clazz)) {
            throw new IllegalStateException("Class ${clazz.canonicalName} is not a domain class")
        }
    }
}
