package grails.plugins.elasticsearch.mapping

import grails.plugins.elasticsearch.ElasticSearchSpec
import grails.testing.mixin.integration.Integration
import org.grails.datastore.mapping.model.MappingContext
import spock.lang.Specification
import spock.lang.Unroll
import test.Person
import test.Photo
import test.Spaceship
import test.transients.*

@Integration
class DomainReflectionSpec extends Specification implements ElasticSearchSpec {

    DomainReflectionService domainReflectionService
    MappingContext mappingContext

    private static final Set<String> TEAM_PROPERTIES =
            ['id', 'version', 'name', 'strip', 'players', 'fans'] as Set

    private static final Set<String> TEAM_PERSISTENT_PROPERTIES =
            ['name', 'strip'] as Set

    private static final Map<String, Class<?>> TEAM_ASSOCIATIONS =
            [players: Player, fans: Fan]

    void setup() {
        resetElasticsearch()
    }

    def "test.transients.Team"() {
        when:
            def team = domainReflectionService.getDomainEntity(Team)

        then:
            with(team) {
                type == Team
                fullName == Team.canonicalName
                packageName == Team.package.name

                delegateMetaClass == Team.metaClass

                root

                identifierName == 'id'
                identifier.name == 'id'
                identifier.type == Long

                defaultPropertyName == 'team'
                propertyNameRepresentation == 'team'

                allProperties.collect { it.name }.toSet() == TEAM_PROPERTIES
                persistentProperties.collect { it.name }.toSet() == TEAM_PERSISTENT_PROPERTIES

                associationMap == TEAM_ASSOCIATIONS
            }

            team.properties.each {
                assert it.domainEntity == team
            }

            TEAM_PROPERTIES.each {
                assert team.hasProperty(it)
            }

            with(team.getPropertyByName('id')) {
                persistent

                type == Long
                name == 'id'
                typePropertyName == 'long'
                referencedPropertyType == Long

                !association
                associationType == null
                referencedDomainEntity == null
            }

            with(team.getPropertyByName('version')) {
                persistent

                type == Long
                name == 'version'
                typePropertyName == 'long'
                referencedPropertyType == Long

                !association
                associationType == null
                referencedDomainEntity == null
            }

            with(team.getPropertyByName('name')) {
                persistent

                type == String
                name == 'name'
                typePropertyName == 'string'
                referencedPropertyType == String

                !association
                associationType == null
                referencedDomainEntity == null
            }

            with(team.getPropertyByName('strip')) {
                persistent

                type == String
                name == 'strip'
                typePropertyName == 'string'
                referencedPropertyType == String

                !association
                associationType == null
                referencedDomainEntity == null
            }

            with(team.getPropertyByName('players')) {
                !persistent

                type == Object
                name == 'players'
                typePropertyName == 'object'
                referencedPropertyType == Player

                association
                associationType == Player
                referencedDomainEntity?.type == Player
            }

            with(team.getPropertyByName('fans')) {
                !persistent

                type == Object
                name == 'fans'
                typePropertyName == 'object'
                referencedPropertyType == Fan

                association
                associationType == Fan
                referencedDomainEntity?.type == Fan
            }
    }

    @Unroll
    def "Team property '#name' isAssociation() returns #association"(String name, boolean association) {
        when:
            def team = domainReflectionService.getDomainEntity(Team)

        then:
            team.getPropertyByName(name).association == association

        where:
            name      | association
            'id'      | false
            'version' | false
            'name'    | false
            'strip'   | false
            'players' | true
            'fans'    | true
    }

    private static final Set<String> PALETTE_PROPERTIES =
            ['id', 'version', 'author', 'description', 'colors', 'tags', 'complementaries'] as Set

    private static final Set<String> PALETTE_PERSISTENT_PROPERTIES =
            ['author', 'colors', 'tags'] as Set

    private static final Map<String, Class<?>> PALETTE_ASSOCIATIONS =
            [colors: Color, tags: String, complementaries: String]

    def "test.transients.Palette"() {
        when:
            def palette = domainReflectionService.getDomainEntity(Palette)

        then:
            with(palette) {
                type == Palette
                fullName == Palette.canonicalName
                packageName == Palette.package.name

                delegateMetaClass == Palette.metaClass

                root

                identifierName == 'id'
                identifier.name == 'id'
                identifier.type == Long

                defaultPropertyName == 'palette'
                propertyNameRepresentation == 'palette'

                allProperties.collect { it.name }.toSet() == PALETTE_PROPERTIES
                persistentProperties.collect { it.name }.toSet() == PALETTE_PERSISTENT_PROPERTIES

                associationMap == PALETTE_ASSOCIATIONS
            }

            palette.properties.each {
                assert it.domainEntity == palette
            }

            PALETTE_PROPERTIES.each {
                assert palette.hasProperty(it)
            }

            with(palette.getPropertyByName('author')) {
                persistent

                type == String
                name == 'author'
                typePropertyName == 'string'
                referencedPropertyType == String

                !association
                associationType == null
                referencedDomainEntity == null
            }

            with(palette.getPropertyByName('description')) {
                !persistent

                type == String
                name == 'description'
                typePropertyName == 'string'
                referencedPropertyType == String

                !association
                associationType == null
                referencedDomainEntity == null
            }

            with(palette.getPropertyByName('colors')) {
                persistent

                type == List
                name == 'colors'
                typePropertyName == 'list'
                referencedPropertyType == Color

                association
                associationType == Color
                referencedDomainEntity == null
            }

            with(palette.getPropertyByName('tags')) {
                persistent

                type == Set
                name == 'tags'
                typePropertyName == 'set'
                referencedPropertyType == String

                association
                associationType == String
                referencedDomainEntity == null
            }

            with(palette.getPropertyByName('complementaries')) {
                !persistent

                type == List
                name == 'complementaries'
                typePropertyName == 'list'
                referencedPropertyType == String

                association
                associationType == String
                referencedDomainEntity == null
            }
    }

    private static final Set<String> SPACESHIP_PROPERTIES =
            ['id', 'version', 'name', 'captain', 'captainId', 'shipData'] as Set

    private static final Set<String> SPACESHIP_PERSISTENT_PROPERTIES =
            ['name', 'captain', 'shipData'] as Set

    private static final Map<String, Class<?>> SPACESHIP_ASSOCIATIONS = [captain: Person]

    def "test.Spaceship"() {
        when:
            def spaceship = domainReflectionService.getDomainEntity(Spaceship)

        then:
            with(spaceship) {
                type == Spaceship
                fullName == Spaceship.canonicalName
                packageName == Spaceship.package.name

                delegateMetaClass == Spaceship.metaClass

                root

                identifierName == 'id'
                identifier.name == 'id'
                identifier.type == Long

                defaultPropertyName == 'spaceship'
                propertyNameRepresentation == 'spaceship'

                allProperties.collect { it.name }.toSet() == SPACESHIP_PROPERTIES
                persistentProperties.collect { it.name }.toSet() == SPACESHIP_PERSISTENT_PROPERTIES

                associationMap == SPACESHIP_ASSOCIATIONS
            }

            with(spaceship.getPropertyByName('name')) {
                persistent

                type == String
                name == 'name'
                typePropertyName == 'string'
                referencedPropertyType == String

                !association
                associationType == null
                referencedDomainEntity == null
            }

            with(spaceship.getPropertyByName('shipData')) {
                persistent

                type == String
                name == 'shipData'
                typePropertyName == 'string'
                referencedPropertyType == String

                !association
                associationType == null
                referencedDomainEntity == null
            }

            with(spaceship.getPropertyByName('captain')) {
                persistent

                type == Person
                name == 'captain'
                typePropertyName == 'person'
                referencedPropertyType == Person

                association
                associationType == Person
                referencedDomainEntity?.type == Person
            }

    }

    private static final Set<String> PHOTO_PROPERTIES =
            ['id', 'version', 'name', 'url', 'size', 'type'] as Set

    private static final Set<String> PHOTO_PERSISTENT_PROPERTIES =
            ['name', 'url', 'size', 'type'] as Set

    private static final Map<String, Class<?>> PHOTO_ASSOCIATIONS = [:]

    def "test.Photo"() {
        when:
            def photo = domainReflectionService.getDomainEntity(Photo)

        then:
            with(photo) {
                type == Photo
                fullName == Photo.canonicalName
                packageName == Photo.package.name

                delegateMetaClass == Photo.metaClass

                !root

                identifierName == 'id'
                identifier.name == 'id'
                identifier.type == Long

                defaultPropertyName == 'photo'
                propertyNameRepresentation == 'photo'

                allProperties.collect { it.name }.toSet() == PHOTO_PROPERTIES
                persistentProperties.collect { it.name }.toSet() == PHOTO_PERSISTENT_PROPERTIES

                associationMap == PHOTO_ASSOCIATIONS
            }

            with(photo.getPropertyByName('name')) {
                persistent

                type == String
                name == 'name'
                typePropertyName == 'string'
                referencedPropertyType == String

                !association
                associationType == null
                referencedDomainEntity == null
            }

            with(photo.getPropertyByName('url')) {
                persistent

                type == String
                name == 'url'
                typePropertyName == 'string'
                referencedPropertyType == String

                !association
                associationType == null
                referencedDomainEntity == null
            }

            with(photo.getPropertyByName('type')) {
                persistent

                type == String
                name == 'type'
                typePropertyName == 'string'
                referencedPropertyType == String

                !association
                associationType == null
                referencedDomainEntity == null
            }

            with(photo.getPropertyByName('size')) {
                persistent

                type == int
                name == 'size'
                typePropertyName == 'int'
                referencedPropertyType == int

                !association
                associationType == null
                referencedDomainEntity == null
            }
    }

}
