/*
 * Copyright 2002-2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package grails.plugins.elasticsearch

import org.elasticsearch.client.Client
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.common.transport.InetSocketTransportAddress
import org.elasticsearch.node.InternalSettingsPreparer
import org.elasticsearch.node.Node
import org.elasticsearch.plugins.Plugin
import org.elasticsearch.transport.client.PreBuiltTransportClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.FactoryBean
import org.springframework.core.io.Resource
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class ClientNodeFactoryBean implements FactoryBean {

    static final SUPPORTED_MODES = ['local', 'transport', 'dataNode']

    private static final Logger LOG = LoggerFactory.getLogger(this)

    ElasticSearchContextHolder elasticSearchContextHolder
    def node

    Object getObject() {
        // Retrieve client mode, default is "node"
        String clientMode = elasticSearchContextHolder.config.client.mode ?: 'transport'
        if (!(clientMode in SUPPORTED_MODES)) {
            throw new IllegalArgumentException("Invalid client mode, expected values were ${SUPPORTED_MODES}.")
        }

        Settings.Builder settings = Settings.builder()
        def configFile = elasticSearchContextHolder.config.bootstrap.config.file
        if (configFile) {
            LOG.info "Looking for bootstrap configuration file at: $configFile"
            Resource resource = new PathMatchingResourcePatternResolver().getResource(configFile)
            settings = settings.loadFromStream(configFile, resource.inputStream)
        }

        def transportClient
        // Cluster name
        if (elasticSearchContextHolder.config.cluster.name) {
            settings.put('cluster.name', elasticSearchContextHolder.config.cluster.name)
        }

        // Path to the data folder of ES
        def dataPath = elasticSearchContextHolder.config.path.data
        if (dataPath) {
            settings.put('path.data', dataPath as String)
            LOG.info "Using ElasticSearch data path: ${dataPath}"
        }

        // Configure the client based on the client mode
        switch (clientMode) {
            case 'transport':
                def transportSettingsBuilder = Settings.builder()

                def transportSettingsFile = elasticSearchContextHolder.config.bootstrap.transportSettings.file
                if (transportSettingsFile) {
                    Resource resource = new PathMatchingResourcePatternResolver().getResource(transportSettingsFile)
                    transportSettingsBuilder.loadFromStream(transportSettingsFile, resource.inputStream)
                }
                // Use the "sniff" feature of transport client ?
                if (elasticSearchContextHolder.config.client.transport.sniff) {
                    transportSettingsBuilder.put("client.transport.sniff", false)
                }
                if (elasticSearchContextHolder.config.cluster.name) {
                    transportSettingsBuilder.put('cluster.name', elasticSearchContextHolder.config.cluster.name.toString())
                }
                def transportSettings = transportSettingsBuilder.build()
                transportClient = new PreBuiltTransportClient(transportSettings, Collections.emptyList());

                boolean ip4Enabled = elasticSearchContextHolder.config.shield.ip4Enabled ?: true
                boolean ip6Enabled = elasticSearchContextHolder.config.shield.ip6Enabled ?: false

                try {
                    def shield = Class.forName("org.elasticsearch.shield.ShieldPlugin")
                    transportClient = new PreBuiltTransportClient(transportSettings, Collections.singletonList(shield));
                    LOG.info("Shield Enabled")
                } catch (ClassNotFoundException e) {
                    transportClient = new PreBuiltTransportClient(transportSettings, Collections.emptyList());
                }

                // Configure transport addresses
                if (!elasticSearchContextHolder.config.client.hosts) {
                    transportClient.addTransportAddress(new InetSocketTransportAddress(new InetSocketAddress('localhost', 9300)))
                } else {
                    def debugEnabled = LOG.isDebugEnabled()
                    elasticSearchContextHolder.config.client.hosts.each {
                        try {
                            if (debugEnabled) {
                                LOG.debug("Trying this host:  ${it.host}")
                            }
                            for (InetAddress address : InetAddress.getAllByName(it.host)) {
                                if (debugEnabled) {
                                    LOG.debug("Gonna add host: ${address.getHostAddress()}:${it.port}")
                                }
                                if ((ip6Enabled && address instanceof Inet6Address) || (ip4Enabled && address instanceof Inet4Address)) {
                                    LOG.info("Adding InetAddress: ${address}:${it.port}")
                                    transportClient.addTransportAddress(new InetSocketTransportAddress(address, Integer.parseInt(it.port)));
                                }
                            }
                        } catch (UnknownHostException e) {
                            LOG.error("Unable to get the host", e.getMessage());
                        }
                    }
                }
                break

            case 'local':
                // Determines how the data is stored (on disk, in memory, ...)
                def storeType = elasticSearchContextHolder.config.index.store.type
                if (storeType) {
                    settings.put('index.store.type', storeType as String)
                    LOG.debug "Local ElasticSearch client with store type of ${storeType} configured."
                } else {
                    LOG.debug "Local ElasticSearch client with default store type configured."
                }
                def gatewayType = elasticSearchContextHolder.config.gateway.type
                if (gatewayType) {
                    settings.put('gateway.type', gatewayType as String)
                    LOG.debug "Local ElasticSearch client with gateway type of ${gatewayType} configured."
                } else {
                    LOG.debug "Local ElasticSearch client with default gateway type configured."
                }
                def queryParsers = elasticSearchContextHolder.config.index.queryparser
                if (queryParsers) {
                    queryParsers.each { type, clz ->
                        settings.put("index.queryparser.types.${type}".toString(), clz)
                    }
                }

                def pluginsDirectory = elasticSearchContextHolder.config.path.plugins
                if (pluginsDirectory) {
                    settings.put('path.plugins', new File(pluginsDirectory as String).absolutePath)
                }

                // Path to the config folder of ES
                def confDirectory = elasticSearchContextHolder.config.path.conf
                if (confDirectory) {
                    settings.put('path.conf', confDirectory as String)
                }

                def tmpDirectory = tmpDirectory()
                LOG.info "Setting embedded ElasticSearch tmp dir to ${tmpDirectory}"
                settings.put("path.home", tmpDirectory)

                //settings.put("node.local", true)
                settings.put("transport.type", "local")
                settings.put("http.enabled", false)
                break

            case 'dataNode':
                def storeType = elasticSearchContextHolder.config.index.store.type
                if (storeType) {
                    settings.put('index.store.type', storeType as String)
                    LOG.debug "DataNode ElasticSearch client with store type of ${storeType} configured."
                } else {
                    LOG.debug "DataNode ElasticSearch client with default store type configured."
                }
                def queryParsers = elasticSearchContextHolder.config.index.queryparser
                if (queryParsers) {
                    queryParsers.each { type, clz ->
                        settings.put("index.queryparser.types.${type}".toString(), clz)
                    }
                }
                if (elasticSearchContextHolder.config.discovery.zen.ping.unicast.hosts) {
                    settings.put("discovery.zen.ping.unicast.hosts", elasticSearchContextHolder.config.discovery.zen.ping.unicast.hosts)
                }

                settings.put("node.data", true)
                break
        }
        if (transportClient) {
            return transportClient
        }

        //Inject http settings...
        if (elasticSearchContextHolder.config.http) {
            flattenMap(elasticSearchContextHolder.config.http).each { p ->
                settings.put("http.${p.key}", p.value as String)
            }
        }

        if(elasticSearchContextHolder.config.plugin.mapperAttachment.enabled) {
            node = new PluginEnabledNode(settings, org.elasticsearch.mapper.attachments.MapperAttachmentsPlugin)
        } else {
            node = new Node(settings.build())
        }
        // Avoiding this:
//        node = new PluginEnabledNode(settings, MapperAttachmentsPlugin)
        node.start()
        def client = node.client()
        // Wait for the cluster to become alive.
        //            LOG.info "Waiting for ElasticSearch GREEN status."
        //            client.admin().cluster().health(new ClusterHealthRequest().waitForGreenStatus()).actionGet()
        return client
    }
    //From http://groovy.329449.n5.nabble.com/Flatten-Map-using-closure-td364360.html
    def flattenMap(map) {
        [:].putAll(map.entrySet().flatten {
            it.value instanceof Map ? it.value.collect { k, v -> new MapEntry(it.key + '.' + k, v) } : it
        })
    }

    @Override
    Class getObjectType() {
        return Client
    }

    @Override
    boolean isSingleton() {
        return true
    }

    def shutdown() {
        if (elasticSearchContextHolder.config.client.mode == 'local' || elasticSearchContextHolder.config.client.mode == 'dataNode' && node) {
            LOG.info "Stopping embedded ElasticSearch."
            node.close()        // close() seems to be more appropriate than stop()
        }
    }

    private String tmpDirectory() {
        String baseDirectory = System.getProperty("java.io.tmpdir") ?: '/tmp'
        Path path = Files.createTempDirectory(Paths.get(baseDirectory), 'elastic-data-' + new Date().time)
        File file = path.toFile()
        file.deleteOnExit()
        return file.absolutePath
    }

    private static class PluginEnabledNode extends Node {
        PluginEnabledNode(Settings.Builder settings, Class<? extends Plugin> ... plugins) {
            super(InternalSettingsPreparer.prepareEnvironment(settings.build(), null), plugins as List<Plugin>)
        }
    }
}
