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

import grails.plugins.Plugin
import grails.plugins.elasticsearch.conversion.CustomEditorRegistrar
import grails.plugins.elasticsearch.conversion.JSONDomainFactory
import grails.plugins.elasticsearch.conversion.unmarshall.DomainClassUnmarshaller
import grails.plugins.elasticsearch.index.IndexRequestQueue
import grails.plugins.elasticsearch.mapping.DomainReflectionService
import grails.plugins.elasticsearch.mapping.MappingMigrationManager
import grails.plugins.elasticsearch.mapping.SearchableClassMappingConfigurator
import grails.plugins.elasticsearch.unwrap.DomainClassUnWrapperChain
import grails.plugins.elasticsearch.unwrap.HibernateProxyUnWrapper
import grails.plugins.elasticsearch.util.DomainDynamicMethodsUtils
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class ElasticsearchGrailsPlugin extends Plugin {

    private static final Logger LOG = LoggerFactory.getLogger(this)

    def grailsVersion = '3.3.1 > *'

    def loadAfter = ['services', 'mongodb']

    def pluginExcludes = [
            "grails-app/views/error.gsp",
                          "**/test/**",
            "src/docs/**"
    ]

    def license = 'APACHE'

    def organization = [name: 'TO THE NEW', url: 'http://www.tothenew.com']

    def developers = [
            [name: 'Noam Y. Tenne', email: 'noam@10ne.org'],
			[name: 'Marcos Carceles', email: 'marcos.carceles@gmail.com'],
			[name: 'Puneet Behl', email: 'puneet.behl007@gmail.com'],
            [name: 'James Kleeh', email: 'james.kleeh@gmail.com']
    ]

    def issueManagement = [system: 'github', url: 'https://github.com/puneetbehl/elasticsearch-grails-plugin/issues']

    def scm = [url: 'https://github.com/puneetbehl/elasticsearch-grails-plugin']

    def author = 'Puneet Behl'
    def authorEmail = 'puneet.behl007@gmail.com'
    def title = 'ElasticSearch Grails Plugin'
    def description = """The revived Elasticsearch plugin for Grails."""
    def documentation = 'http://puneetbehl.github.io/elasticsearch-grails-plugin'

    def profiles = ['web']

    Closure doWithSpring() {
        { ->
            ConfigObject esConfig = config.elasticSearch

            domainReflectionService(DomainReflectionService) { bean ->
                mappingContext = ref('grailsDomainClassMappingContext')

                grailsApplication = grailsApplication
            }

            elasticSearchContextHolder(ElasticSearchContextHolder) {
                config = esConfig
            }
            elasticSearchHelper(ElasticSearchHelper) {
                elasticSearchClient = ref('elasticSearchClient')
            }
            elasticSearchClient(ClientNodeFactoryBean) { bean ->
                elasticSearchContextHolder = ref('elasticSearchContextHolder')
                bean.destroyMethod = 'shutdown'
            }
            indexRequestQueue(IndexRequestQueue) {
                elasticSearchContextHolder = ref('elasticSearchContextHolder')
                elasticSearchClient = ref('elasticSearchClient')
                jsonDomainFactory = ref('jsonDomainFactory')
                domainClassUnWrapperChain = ref('domainClassUnWrapperChain')
            }
            mappingMigrationManager(MappingMigrationManager) {
                elasticSearchContextHolder = ref('elasticSearchContextHolder')
                grailsApplication = grailsApplication
                es = ref('elasticSearchAdminService')
            }
            searchableClassMappingConfigurator(SearchableClassMappingConfigurator) { bean ->
                elasticSearchContext = ref('elasticSearchContextHolder')
                grailsApplication = grailsApplication
                es = ref('elasticSearchAdminService')
                mmm = ref('mappingMigrationManager')
                domainReflectionService = ref('domainReflectionService')
            }
            domainInstancesRebuilder(DomainClassUnmarshaller) {
                elasticSearchContextHolder = ref('elasticSearchContextHolder')
                elasticSearchClient = ref('elasticSearchClient')
                grailsApplication = grailsApplication
            }
            customEditorRegistrar(CustomEditorRegistrar) {
                grailsApplication = grailsApplication
            }

            if (manager?.hasGrailsPlugin('hibernate') || manager?.hasGrailsPlugin('hibernate4')) {
                hibernateProxyUnWrapper(HibernateProxyUnWrapper)
            }

            domainClassUnWrapperChain(DomainClassUnWrapperChain)

            jsonDomainFactory(JSONDomainFactory) {
                elasticSearchContextHolder = ref('elasticSearchContextHolder')
                grailsApplication = grailsApplication
                domainClassUnWrapperChain = ref('domainClassUnWrapperChain')
                domainReflectionService = ref('domainReflectionService')
            }

            elasticSearchBootStrapHelper(ElasticSearchBootStrapHelper) {
                grailsApplication = grailsApplication
                elasticSearchService = ref('elasticSearchService')
                elasticSearchContextHolder = ref('elasticSearchContextHolder')
                elasticSearchAdminService = ref('elasticSearchAdminService')
            }

            if (!esConfig.disableAutoIndex) {
                if (!esConfig.datastoreImpl) {
                    throw new Exception('No datastore implementation specified')
                }
                auditListener(AuditEventListener, ref(esConfig.datastoreImpl)) {
                    elasticSearchContextHolder = ref('elasticSearchContextHolder')
                    indexRequestQueue = ref('indexRequestQueue')
                }
            }
        }
    }

    @Override
    void doWithApplicationContext() {
        def configurator = applicationContext.getBean(SearchableClassMappingConfigurator)
        configurator.configureAndInstallMappings()

        if (!grailsApplication.config.getProperty("elasticSearch.disableDynamicMethodsInjection", Boolean, false)) {
            DomainDynamicMethodsUtils.injectDynamicMethods(grailsApplication, applicationContext)
        }
    }

}
