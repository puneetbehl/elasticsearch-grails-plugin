package de.cgoit.grails.plugins.elasticsearch

import spock.lang.Specification
import spock.lang.Unroll

class ConnectionStringSpec extends Specification {

    @Unroll
    void "a valid connection string (#configValue) with single elasticsearch node should properly return the host and port"() {

        when:
        ConnectionString connectionString = new ConnectionString(configValue)

        then:
        connectionString.hosts == hosts
        connectionString.connections == connections

        where:
        configValue                                                      || hosts                                              |
                connections
        "elasticsearch://user:pwd@elasticsearch.dev.internal:9300"       || ["elasticsearch.dev.internal:9300"]                | [[host: 'elasticsearch.dev.internal', port: 9300]]
        "elasticsearch://elasticsearch.dev.internal:9300"                || ["elasticsearch.dev.internal:9300"]                | [[host: 'elasticsearch.dev.internal', port: 9300]]
        "elasticsearch://example.com:9300"                               || ["example.com:9300"]                               | [[host: 'example.com', port: 9300]]
        "elasticsearch://localhost:9300"                                 || ["localhost:9300"]                                 | [[host: 'localhost', port: 9300]]
        "elasticsearch://127.0.0.1:9300"                                 || ["127.0.0.1:9300"]                                 | [[host: '127.0.0.1', port: 9300]]
        "elasticsearch://127.0.0.1:9200"                                 || ["127.0.0.1:9200"]                                 | [[host: '127.0.0.1', port: 9200]]
        "elasticsearch://[2001:0db8:85a3:0000:0000:8a2e:0370:7334]:9200" || ["[2001:0db8:85a3:0000:0000:8a2e:0370:7334]:9200"] | [[host: '2001:0db8:85a3:0000:0000:8a2e:0370:7334', port: 9200]]
    }

    @Unroll
    void "a valid connection string(#configValue) for replica-set should properly return host and port"() {
        when:
        ConnectionString connectionString = new ConnectionString(configValue)

        then:
        connectionString.hosts == hosts
        connectionString.connections == connections

        where:
        configValue                                                                        || hosts                                                                   |
                connections

        "elasticsearch://elasticsearch.dev.internal:9300,elasticsearch2.dev.internal:9300" || ["elasticsearch.dev.internal:9300", "elasticsearch2.dev.internal:9300"] | [[host: 'elasticsearch.dev.internal', port: 9300], [host: 'elasticsearch2.dev.internal', port: 9300]]

        "elasticsearch://example1.com:9300,example0.com:9400"                              || ["example0.com:9400", "example1.com:9300"]                              | [[host: 'example0.com', port: 9400], [host: 'example1.com', port: 9300]]

        "elasticsearch://localhost:9300,"                                                  || ["localhost:9300"]                                                      | [[host: 'localhost', port: 9300]]

        "elasticsearch://127.0.0.1:9200,127.0.0.2:9400"                                    || ["127.0.0.1:9200", "127.0.0.2:9400"]                                    | [[host: '127.0.0.1', port: 9200], [host: '127.0.0.2', port: 9400]]
    }

    @Unroll
    void "an invalid connection(#configValue) string should should throw IllegalArgumentException"() {
        when:
        new ConnectionString(configValue)

        then:
        thrown(IllegalArgumentException)

        where:
        configValue << [
                "elasticsearch.dev.internal:9300",
                "elasticsearch://user@pwd@123@example.com:9300",
                "elasticsearch://user@pwd:123@example.com:9300",
                "elasticsearch://"]
    }

    void "an invalid port value 65539 in connection string should throw IllegalArgumentException"() {
        when:
        new ConnectionString("elasticsearch://127.0.0.1:65539")

        then:
        thrown(IllegalArgumentException)
    }

    @Unroll
    void "an invalid ipv6  (#configValue) address should throw IllegalArgumentException"() {
        when:
        new ConnectionString(configValue)

        then:
        thrown(IllegalArgumentException)

        where:
        configValue << [
                "elasticsearch://[2001:0db8:85a3:0000:0000:8a2e:0370:7334:9300",
                "elasticsearch://2001:0db8:85a3:0000:0000:8a2e:0370:7334]:9300",
                "elasticsearch://2001:0db8:85a3:0000:0000:8a2e:0370:7334:9300"
        ]
    }

    void "an invalid ipv4 address should throw IllegalArgumentException"() {
        when:
        new ConnectionString("elasticsearch://localhost:")

        then:
        thrown(IllegalArgumentException)
    }

    void "a non-integer port value in connection string should throw ElasticsearchException"() {
        when:
        new ConnectionString("elasticsearch://127.0.0.1:test")

        then:
        thrown(IllegalArgumentException)
    }
}
