package de.cgoit.grails.plugins.elasticsearch.mapping

import grails.util.GrailsClassUtils
import grails.util.GrailsNameUtils
import groovy.transform.CompileStatic
import org.grails.datastore.mapping.model.PersistentEntity
import org.grails.datastore.mapping.model.PersistentProperty
import org.grails.datastore.mapping.model.config.GormProperties
import org.grails.datastore.mapping.reflect.ClassPropertyFetcher

import java.lang.reflect.Modifier

@CompileStatic
class DomainEntity {

    private final DomainReflectionService reflectionService
    private final Class<?> entityClass
    private final PersistentEntity persistentEntity
    private final Map<String, DomainProperty> propertyCache = [:]
    private Map<String, Class<?>> _associationMap
    private final ClassPropertyFetcher cpf

    private static final List<String> IGNORED_PROPERTIES = []

    static {
        IGNORED_PROPERTIES.add(GormProperties.DIRTY_PROPERTY_NAMES)
        IGNORED_PROPERTIES.add(GormProperties.ERRORS)
        IGNORED_PROPERTIES.add(GormProperties.DIRTY)
        IGNORED_PROPERTIES.add(GormProperties.ATTACHED)
        IGNORED_PROPERTIES.add(GormProperties.PROPERTIES)
        IGNORED_PROPERTIES.add(GormProperties.META_CLASS)
        // GORM for mongodb ignores
        IGNORED_PROPERTIES.add("dbo")
    }

    DomainEntity(DomainReflectionService reflectionService, Class<?> entityClass) {
        this(reflectionService, null, entityClass)
    }

    DomainEntity(DomainReflectionService reflectionService, PersistentEntity persistentEntity) {
        this(reflectionService, persistentEntity, persistentEntity.javaClass)
    }

    private DomainEntity(DomainReflectionService reflectionService,
            PersistentEntity persistentEntity,
            Class<?> entityClass) {

        this.persistentEntity = persistentEntity
        this.reflectionService = reflectionService
        this.entityClass = entityClass
        this.cpf = ClassPropertyFetcher.forClass(entityClass)
    }

    PersistentEntity getPersistentEntity() {
        return persistentEntity
    }

    Class<?> getType() {
        entityClass
    }

    String getFullName() {
        persistentEntity.name
    }

    String getPackageName() {
        persistentEntity.javaClass.package.name
    }

    MetaClass getDelegateMetaClass() {
        persistentEntity.javaClass.metaClass
    }

    boolean isRoot() {
        persistentEntity.isRoot()
    }

    DomainProperty getIdentifier() {
        getPropertyAdapter(persistentEntity.identity)
    }

    String getIdentifierName() {
        persistentEntity.identity.name
    }

    String getDefaultPropertyName() {
        persistentEntity.decapitalizedName
    }

    String getPropertyNameRepresentation() {
        GrailsNameUtils.getPropertyName(type)
    }

    boolean hasProperty(String name) {
        cpf.metaProperties.find { it.name == name }
    }

    boolean hasSearchableProperty(String searchablePropertyName) {
        entityClass.declaredFields.find { it.name == searchablePropertyName && Modifier.isStatic(it.modifiers) }
    }

    DomainProperty getPropertyByName(String name) {
        PersistentProperty persistentProperty = persistentEntity.getPropertyByName(name)
        if (persistentProperty != null) {
            return getPropertyAdapter(persistentProperty)
        }
        MetaProperty metaProperty = cpf.getMetaProperties().find { it.name.equals(name) }
        if (metaProperty != null) {
            return getPropertyAdapter(metaProperty)
        }

        null
    }

    Collection<DomainProperty> getProperties() {
        allProperties
    }

    Collection<DomainProperty> getAllProperties() {
        Collection<DomainProperty> allProps = []
        final List<PersistentProperty> persistentProperties = persistentEntity.persistentProperties
        persistentProperties
                .forEach({ PersistentProperty property ->
                    if (!(property.name in IGNORED_PROPERTIES)) {
                        allProps.add(getPropertyAdapter(property))
                    }
                })
        cpf.getMetaProperties().forEach({ MetaProperty metaProperty ->
            if (!(metaProperty.name in IGNORED_PROPERTIES) &&
                    !Modifier.isStatic(metaProperty.modifiers) &&
                    notInPersistentProperties(persistentProperties, metaProperty)) {

                allProps.add(getPropertyAdapter(metaProperty))
            }
        })
        allProps
    }

    Collection<DomainProperty> getPersistentProperties() {
        persistentEntity.persistentProperties
                .collect {
                    if (it.name in IGNORED_PROPERTIES || it.name == GormProperties.VERSION) {
                        return null
                    }
                    getPropertyAdapter(it)
                }
                .findAll { it }
    }

    Object getInitialPropertyValue(String name) {
        ClassPropertyFetcher.forClass(persistentEntity.javaClass).getPropertyValue(name)
    }

    boolean isPropertyInherited(DomainProperty property) {
        GrailsClassUtils.isPropertyInherited(entityClass, property.name)
    }

    Class<?> getRelatedClassType(String propertyName) {
        getAssociationForProperty(propertyName)
    }

    Map<String, Class<?>> getAssociationMap() {
        if (!_associationMap) {
            _associationMap = getMergedConfigurationMap(type, GormProperties.HAS_MANY)

            getProperties().each {
                if (reflectionService.isDomainEntity(it.type)) {
                    _associationMap[it.name] = it.type
                }
            }
        }

        _associationMap
    }

    boolean isAssociation(String propertyName) {
        associationMap.containsKey(propertyName)
    }

    Class<?> getAssociationForProperty(String propertyName) {
        associationMap[propertyName]
    }

    @Override
    String toString() {
        "DomainEntity{type=${type.canonicalName}}"
    }

    private static Map getMergedConfigurationMap(Class<?> clazz, String propertyName) {
        Map configurationMap = getStaticPropertyValue(clazz, propertyName, Map) ?: new HashMap<>()

        Class<?> superClass = clazz
        while (superClass != Object.class) {
            superClass = superClass.getSuperclass()
            Map superRelationshipMap = getStaticPropertyValue(superClass, propertyName, Map)
            if (superRelationshipMap != null && superRelationshipMap != configurationMap) {
                configurationMap.putAll(superRelationshipMap)
            }
        }
        return configurationMap
    }

    private static <T> T getStaticPropertyValue(Class<?> clazz, String propertyName, Class<T> propertyClass) {
        ClassPropertyFetcher.forClass(clazz).getStaticPropertyValue(propertyName, propertyClass)
    }

    private DomainProperty getPropertyAdapter(PersistentProperty property) {
        if (!property) {
            return null
        }

        propertyCache.computeIfAbsent(property.name) {
            new DomainProperty(reflectionService, this, property)
        }
    }

    private DomainProperty getPropertyAdapter(MetaProperty property) {
        if (!property) {
            return null
        }

        propertyCache.computeIfAbsent(property.name) {
            new DomainProperty(reflectionService, this, null, property)
        }
    }

    @SuppressWarnings("GrMethodMayBeStatic")
    private boolean notInPersistentProperties(List<PersistentProperty> persistentProperties,
            MetaProperty metaProperty) {
        !persistentProperties.find { (it.name == metaProperty.name) }
    }
}
