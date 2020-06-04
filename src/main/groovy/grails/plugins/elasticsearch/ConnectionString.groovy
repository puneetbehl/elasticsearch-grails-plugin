package grails.plugins.elasticsearch

import groovy.util.logging.Slf4j
import org.elasticsearch.ElasticsearchException

import static java.lang.String.format
import static java.util.Arrays.asList

@Slf4j
class ConnectionString {

    private static final String PREFIX = "elasticsearch://"
    private static final String UTF_8 = "UTF-8"
    private static final String DEFAULT_HOST = 'localhost'
    private static final int DEFAULT_PORT = 9300

    private final String connectionString
    private final List<String> hosts
    private final List<Map> connections = []

    ConnectionString(final String connectionString) {
        this.connectionString = connectionString
        if (!connectionString.startsWith(PREFIX)) {
            throw new IllegalArgumentException(format("The connection string is invalid. "
                    + "Connection strings must start with '%s'", PREFIX))
        }

        String unprocessedConnectionString = connectionString.substring(PREFIX.length())

        String userAndHostInformation = null
        int idx = unprocessedConnectionString.lastIndexOf("/")
        if (idx == -1) {
            if (unprocessedConnectionString.contains("?")) {
                throw new IllegalArgumentException("The connection string contains options without trailing slash")
            }
            userAndHostInformation = unprocessedConnectionString
            unprocessedConnectionString = ""
        } else {
            userAndHostInformation = unprocessedConnectionString.substring(0, idx)
            unprocessedConnectionString = unprocessedConnectionString.substring(idx + 1)
        }

        String userInfo = null
        String hostIdentifier = null
        String userName = null
        char[] password = null

        idx = userAndHostInformation.lastIndexOf("@")

        if (idx > 0) {
            userInfo = userAndHostInformation.substring(0, idx)
            hostIdentifier = userAndHostInformation.substring(idx + 1)
            int colonCount = countOccurrences(userInfo, ":")
            if (userInfo.contains("@") || colonCount > 1) {
                throw new IllegalArgumentException("The connection string contains invalid user information. "
                        + "If the username or password contains a colon (:) or an at-sign (@) then it must be urlencoded")
            }
            if (colonCount == 0) {
                userName = urldecode(userInfo)
            } else {
                idx = userInfo.indexOf(":")
                userName = urldecode(userInfo.substring(0, idx))
                password = urldecode(userInfo.substring(idx + 1), true).toCharArray()
            }
        } else {
            hostIdentifier = userAndHostInformation
        }

        hosts = Collections.unmodifiableList(parseHosts(asList(hostIdentifier.split(","))))

        for (String host in hosts) {
            String hostToUse = host
            if (hostToUse == null) {
                hostToUse = DEFAULT_HOST
            }
            hostToUse = hostToUse.trim()
            if (hostToUse.length() == 0) {
                hostToUse = DEFAULT_HOST
            }

            int portToUse = DEFAULT_PORT
            if (hostToUse.startsWith("[")) {
                idx = host.indexOf("]")
                if (idx == -1) {
                    throw new IllegalArgumentException("an IPV6 address must be encosed with '[' and ']'"
                            + " according to RFC 2732.")
                }

                int portIdx = host.indexOf("]:")
                if (portIdx != -1) {
                    portToUse = Integer.parseInt(host.substring(portIdx + 2))
                }
                hostToUse = host.substring(1, idx)
            } else {
                idx = hostToUse.indexOf(":")
                int lastIdx = hostToUse.lastIndexOf(":")
                if (idx == lastIdx && idx > 0) {
                    try {
                        portToUse = Integer.parseInt(hostToUse.substring(idx + 1))
                    } catch (NumberFormatException e) {
                        throw new ElasticsearchException("host and port should be specified in host:port format")
                    }
                    hostToUse = hostToUse.substring(0, idx).trim()
                }
            }
            connections.add([host: hostToUse, port: portToUse])
        }
    }

    private List<String> parseHosts(final List<String> rawHosts) {
        if (rawHosts.size() == 0) {
            throw new IllegalArgumentException("The connection string must contain at least one host")
        }
        List<String> hosts = new ArrayList<String>()
        for (String host : rawHosts) {
            if (host.length() == 0) {
                throw new IllegalArgumentException(
                        format("The connection string contains an empty host '%s'. ", rawHosts))
            } else if (host.endsWith(".sock")) {
                throw new IllegalArgumentException(format("The connection string contains an invalid host '%s'. "
                        + "Unix Domain Socket which is not supported by the Java driver", host))
            } else if (host.startsWith("[")) {
                if (!host.contains("]")) {
                    throw new IllegalArgumentException(format("The connection string contains an invalid host '%s'. "
                            + "IPv6 address literals must be enclosed in '[' and ']' according to RFC 2732", host))
                }
                int idx = host.indexOf("]:")
                if (idx != -1) {
                    validatePort(host, host.substring(idx + 2))
                }
            } else {
                int colonCount = countOccurrences(host, ":")
                if (colonCount > 1) {
                    throw new IllegalArgumentException(format("The connection string contains an invalid host '%s'. "
                            + "Reserved characters such as ':' must be escaped according RFC 2396. "
                            + "Any IPv6 address literal must be enclosed in '[' and ']' according to RFC 2732.", host))
                } else if (colonCount == 1) {
                    validatePort(host, host.substring(host.indexOf(":") + 1))
                }
            }
            hosts.add(host)
        }
        Collections.sort(hosts)
        hosts
    }

    private void validatePort(final String host, final String port) {
        boolean invalidPort = false
        try {
            int portInt = Integer.parseInt(port)
            if (portInt <= 0 || portInt > 65535) {
                invalidPort = true
            }
        } catch (NumberFormatException e) {
            invalidPort = true
        }
        if (invalidPort) {
            throw new IllegalArgumentException(format("The connection string contains an invalid host '%s'. "
                    + "The port '%s' is not a valid, it must be an integer between 0 and 65535", host, port))
        }
    }

    private int countOccurrences(final String haystack, final String needle) {
        return haystack.length() - haystack.replace(needle, "").length()
    }

    private String urldecode(final String input) {
        return urldecode(input, false)
    }

    private String urldecode(final String input, final boolean password) {
        try {
            return URLDecoder.decode(input, UTF_8)
        } catch (UnsupportedEncodingException e) {
            if (password) {
                throw new IllegalArgumentException(
                        "The connection string contained unsupported characters in the password.")
            } else {
                throw new IllegalArgumentException(
                        format("The connection string contained unsupported characters: '%s'."
                                + "Decoding produced the following error: %s", input, e.getMessage()))
            }
        }
    }

    String getConnectionString() {
        return connectionString
    }

    List<String> getHosts() {
        return hosts
    }

    List<Map> getConnections() {
        return connections
    }
}
