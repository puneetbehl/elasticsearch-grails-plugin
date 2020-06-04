package org.grails.plugins.elasticsearch

import spock.lang.Specification

class AuditEventListenerRootIndexSpec extends Specification {

    AuditEventListener listener = new AuditEventListener(null)

    def setup() {
        listener.elasticSearchContextHolder = Mock(ElasticSearchContextHolder)
        listener.elasticSearchContextHolder.isRootClass(_) >> { List args ->
            return args[0] == IndexRootA || args[0] == IndexRootB
        }
    }

    def "an entity with root = true should be returned"() {
        given: "an instance of an entity that is the root of the search index"
        IndexRootA a = new IndexRootA()

        when: "asked for the root of the search index"
        Set roots = listener.getRootIndexedEntity(a)

        then: "a should be returned since it is the index root"
        roots == [a] as Set
    }

    def "an entity with no searchable property should result in an empty list"() {
        given: "an instance of an entity that is not searchable"
        NoParent g = new NoParent()

        when: "asked for the root of the search index"
        Set roots = listener.getRootIndexedEntity(g)

        then: "an empty list should be returned since the entity is not searchable"
        roots.isEmpty()
    }

    def "an entity with no back reference to the parent should result in an empty list"() {
        given: "an instance of an entity with no back reference to its parent"
        NoBackReference h = new NoBackReference()

        when: "asked for the root of the search index"
        Set roots = listener.getRootIndexedEntity(h)

        then: "an empty list should be returned since the entity does not have a back reference to its parent"
        roots.isEmpty()
    }

    def "a searchable entity whose parent is the root should result in a list containing the parent"() {
        given: "an instance of a searchable entity whose parent is the index root"
        ParentIsRoot d = new ParentIsRoot(a: new IndexRootA())

        when: "asked for the root of the search index"
        Set roots = listener.getRootIndexedEntity(d)

        then: "a single item list should be returned containing the parent entity"
        roots[0] == d.a
    }

    def "a searchable entity with two parents which are index roots should result in a list containing both parents"() {
        given: "an instance of a searchable entity with two parents which are index roots"
        TwoParents c = new TwoParents(a: new IndexRootA(), b: new IndexRootB())

        when: "asked for the root of the search index"
        Set roots = listener.getRootIndexedEntity(c)

        then: "a list containing both parents should be returned"
        roots[0] == c.a
        roots[1] == c.b
    }

    def "a searchable entity whose grandparent is the index root should result in a list containing the grandparent"() {
        given: "an instance of a searchable entity whose grandparent is the index root"
        GrandParentIsRoot e = new GrandParentIsRoot(d: new ParentIsRoot(a: new IndexRootA()))

        when: "asked for the root of the search index"
        Set roots = listener.getRootIndexedEntity(e)

        then: "a single item list should be returned containing the grandparent entity"
        roots[0] == e.d.a
    }

    def "a searchable entity whose parent is not searchable should result in an empty list"() {
        given: "an instance of a searchable entity whose parent is not searchable"
        NonSearchableParent i = new NonSearchableParent(h: new NoParent())

        when: "asked for the root of the search index"
        Set roots = listener.getRootIndexedEntity(i)

        then: "an empty list should be returned because the entity is not the root and its parent is not searchable"
        roots.isEmpty()
    }
}

class IndexRootA {
    static searchable = { root = true }
}

class IndexRootB {
    static searchable = true
}

class TwoParents {
    static searchable = { root = false }
    IndexRootA a
    IndexRootB b
    static belongsTo = [a: IndexRootA, b: IndexRootB]
}

class ParentIsRoot {
    static searchable = { root = false }
    IndexRootA a
    static belongsTo = [a: IndexRootA]
}

class GrandParentIsRoot {
    static searchable = { root = false }
    ParentIsRoot d
    static belongsTo = [d: ParentIsRoot]
}

class NoParent {}

class NoBackReference {
    static belongsTo = [IndexRootA]
}

class NonSearchableParent {
    static searchable = { root = false }
    NoParent h
    static belongsTo = [NoParent]
}