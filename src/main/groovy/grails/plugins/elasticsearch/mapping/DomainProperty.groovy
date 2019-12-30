package grails.plugins.elasticsearch.mapping

import grails.util.GrailsNameUtils
import groovy.transform.CompileStatic
import org.grails.datastore.mapping.model.PersistentProperty

@CompileStatic
class DomainProperty {

    private final DomainReflectionService reflectionService
    private final DomainEntity owningEntity
    private final PersistentProperty persistenceProperty
    private final MetaProperty metaProperty

    DomainProperty(DomainReflectionService reflectionService,
                   DomainEntity owningEntity,
                   PersistentProperty persistentProperty,
                   MetaProperty metaProperty = null) {

        this.reflectionService = reflectionService
        this.owningEntity = owningEntity
        this.persistenceProperty = persistentProperty
        this.metaProperty = metaProperty
    }

    boolean isPersistent() {
        persistenceProperty
    }

    DomainEntity getDomainEntity() {
        owningEntity
    }

    Class<?> getType() {
        persistenceProperty?.type ?: metaProperty?.type
    }

    String getName() {
        persistenceProperty?.name ?: metaProperty?.name
    }

    Class<?> getReferencedPropertyType() {
        (association) ? associationType : type
    }

    boolean isAssociation() {
        domainEntity.isAssociation(name)
    }

    Class<?> getAssociationType() {
        domainEntity.getAssociationForProperty(name)
    }

    DomainEntity getReferencedDomainEntity() {
        (association) ? reflectionService.getDomainEntity(associationType) : null
    }

    String getTypePropertyName() {
        String className = persistenceProperty?.type?.name ?: metaProperty?.type?.name
        if (className) GrailsNameUtils.getPropertyName(className)
    }

    @Override
    String toString() {
        "DomainProperty{name=$name, type=${type.simpleName}, domainClass=$domainEntity.fullName}"
    }

}
