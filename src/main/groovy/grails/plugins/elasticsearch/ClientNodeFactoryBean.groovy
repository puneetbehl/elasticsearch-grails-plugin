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

import org.apache.http.HttpHost
import org.apache.http.auth.AuthScope
import org.apache.http.auth.UsernamePasswordCredentials
import org.apache.http.client.CredentialsProvider
import org.apache.http.client.config.RequestConfig
import org.apache.http.impl.client.BasicCredentialsProvider
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder
import org.elasticsearch.client.Client
import org.elasticsearch.client.RestClient
import org.elasticsearch.client.RestClientBuilder
import org.elasticsearch.client.RestHighLevelClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.FactoryBean

class ClientNodeFactoryBean implements FactoryBean {

    private static final Logger LOG = LoggerFactory.getLogger(this)

    ElasticSearchContextHolder elasticSearchContextHolder
    RestHighLevelClient restHighLevelClient

    Object getObject() {

        int connectTimeout = 2
        int socketTimeout = 30

        RestClientBuilder builder = null

        // Configure transport addresses
        if (!elasticSearchContextHolder.config.client.hosts) {
            builder = RestClient.builder(new HttpHost('localhost', 9300))
        } else {
            List<HttpHost> httpHostList = []
            elasticSearchContextHolder.config.client.hosts.each {
                int port = it.port
                httpHostList << new HttpHost("${it.host}", port)
            }
            HttpHost[] httpHosts = httpHostList
            builder = RestClient.builder(httpHosts)
        }

        // Configure username and password credentials
        if (elasticSearchContextHolder.config.client.username) {
            final CredentialsProvider credentialsProvider = new BasicCredentialsProvider()
            credentialsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(elasticSearchContextHolder.config.client.username, elasticSearchContextHolder.config.client.password))
            builder.setHttpClientConfigCallback(new RestClientBuilder.HttpClientConfigCallback() {
                @Override
                HttpAsyncClientBuilder customizeHttpClient(HttpAsyncClientBuilder httpClientBuilder) {
                    return httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider)
                }
            })
        }

        LOG.debug 'Initializing Elasticsearch RestClient'
        //builder.setMaxRetryTimeoutMillis(timeout * 1000)
        builder.setRequestConfigCallback(new RestClientBuilder.RequestConfigCallback() {
            @Override
            RequestConfig.Builder customizeRequestConfig(RequestConfig.Builder requestConfigBuilder) {
                if (elasticSearchContextHolder.config.client.connectTimeout) {
                    connectTimeout = elasticSearchContextHolder.config.client.connectTimeout as int
                    LOG.debug "Set REST client connect timeout to ${connectTimeout} seconds"
                }
                if (elasticSearchContextHolder.config.client.socketTimeout) {
                    socketTimeout = elasticSearchContextHolder.config.client.socketTimeout as int
                    LOG.debug "Set REST client socket timeout to ${socketTimeout} seconds"
                }
                return requestConfigBuilder.setConnectTimeout(connectTimeout * 1000).setSocketTimeout(socketTimeout * 1000)
                        .setConnectionRequestTimeout(0);
            }
        })

        restHighLevelClient = new RestHighLevelClient(builder)
        LOG.debug 'Initialized Elasticsearch RestClient'

        return restHighLevelClient

        /*
        final CredentialsProvider credentialsProvider = new BasicCredentialsProvider()
        credentialsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(elasticSearchContextHolder.config.client.username, elasticSearchContextHolder.config.client.password))
        println "Initializing Elasticsearch RestClient"
        RestClientBuilder builder = RestClient
                .builder(new HttpHost(elasticSearchContextHolder.config.client.host, 9243, "https"))
                .setHttpClientConfigCallback(new RestClientBuilder.HttpClientConfigCallback() {
            @Override
            HttpAsyncClientBuilder customizeHttpClient(HttpAsyncClientBuilder httpClientBuilder) {
                return httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider)
            }
        })
        builder.setMaxRetryTimeoutMillis(timeout * 1000)
        builder.setRequestConfigCallback(new RestClientBuilder.RequestConfigCallback() {
            @Override
            RequestConfig.Builder customizeRequestConfig(RequestConfig.Builder requestConfigBuilder) {
                return requestConfigBuilder.setConnectTimeout(timeout * 1000).setSocketTimeout(timeout * 1000)
                        .setConnectionRequestTimeout(0);
            }
        })

        restClient = new RestHighLevelClient(builder)
        println "Initialized Elasticsearch RestClient"

        return restClient
        */
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
        LOG.info 'Closing RestClient'
        restHighLevelClient.close()
    }
}
