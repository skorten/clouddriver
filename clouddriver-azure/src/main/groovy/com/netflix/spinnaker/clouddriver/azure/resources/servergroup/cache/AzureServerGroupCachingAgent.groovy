/*
 * Copyright 2016 The original authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.clouddriver.azure.resources.servergroup.cache

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.spectator.api.Registry
import com.netflix.spinnaker.cats.agent.AgentDataType
import com.netflix.spinnaker.cats.agent.CacheResult
import com.netflix.spinnaker.cats.agent.DefaultCacheResult
import com.netflix.spinnaker.cats.cache.CacheData
import com.netflix.spinnaker.cats.cache.DefaultCacheData
import com.netflix.spinnaker.cats.cache.RelationshipCacheFilter
import com.netflix.spinnaker.cats.provider.ProviderCache
import com.netflix.spinnaker.clouddriver.azure.AzureCloudProvider
import com.netflix.spinnaker.clouddriver.azure.common.AzureUtilities
import com.netflix.spinnaker.clouddriver.azure.common.cache.AzureCachingAgent
import com.netflix.spinnaker.clouddriver.azure.common.cache.MutableCacheData
import com.netflix.spinnaker.clouddriver.azure.resources.common.cache.Keys
import static com.netflix.spinnaker.clouddriver.azure.resources.common.cache.Keys.Namespace.*
import com.netflix.spinnaker.clouddriver.azure.resources.servergroup.model.AzureServerGroupDescription
import com.netflix.spinnaker.clouddriver.azure.security.AzureCredentials
import com.netflix.spinnaker.clouddriver.cache.OnDemandAgent
import com.netflix.spinnaker.clouddriver.cache.OnDemandMetricsSupport
import groovy.util.logging.Slf4j

import static com.netflix.spinnaker.cats.agent.AgentDataType.Authority.AUTHORITATIVE
import static com.netflix.spinnaker.cats.agent.AgentDataType.Authority.INFORMATIVE

@Slf4j
class AzureServerGroupCachingAgent extends AzureCachingAgent {

  final Registry registry
  final OnDemandMetricsSupport metricsSupport

  AzureServerGroupCachingAgent(AzureCloudProvider azureCloudProvider, String accountName, AzureCredentials creds, String region, ObjectMapper objectMapper, Registry registry) {
    super(azureCloudProvider, accountName, creds, region, objectMapper)

    this.registry = registry
    this.metricsSupport = new OnDemandMetricsSupport(registry, this, "${azureCloudProvider.id}:${onDemandType}")

  }

  @Override
  CacheResult loadData(ProviderCache providerCache) {
    def start = System.currentTimeMillis()

    List<AzureServerGroupDescription> serverGroups = creds.computeClient.getServerGroupsAll(region)

    Collection<String> keys = serverGroups.collect {Keys.getServerGroupKey(AzureCloudProvider.AZURE, it.name, region, accountName ) }
    def onDemandCacheResults = providerCache.getAll(AZURE_ON_DEMAND.ns, keys, RelationshipCacheFilter.none())

    def (evictions, usableOnDemandCacheData) = parseOnDemandCache(onDemandCacheResults, start)
    def result = buildCacheResult(providerCache, serverGroups, usableOnDemandCacheData, evictions)

    def cacheResults = result.cacheResults
    log.info("Caching ${cacheResults[AZURE_APPLICATIONS.ns].size()} applications in ${agentType}")
    log.info("Caching ${cacheResults[AZURE_CLUSTERS.ns].size()} clusters in ${agentType}")
    log.info("Caching ${cacheResults[AZURE_SERVER_GROUPS.ns].size()} server groups in ${agentType}")
    log.info("Caching ${cacheResults[AZURE_INSTANCES.ns].size()} instances in ${agentType}")

    result.cacheResults[AZURE_ON_DEMAND.ns].each {
      it.attributes.processedTime = System.currentTimeMillis()
      it.attributes.processedCount = (it.attributes.processedCount ?: 0) + 1
    }

    result
  }

  @Override
  Set<AgentDataType> initializeTypes() {
    Collections.unmodifiableSet([
      AUTHORITATIVE.forType(AZURE_SERVER_GROUPS.ns),
      INFORMATIVE.forType(AZURE_APPLICATIONS.ns),
      INFORMATIVE.forType(AZURE_CLUSTERS.ns),
      INFORMATIVE.forType(AZURE_INSTANCES.ns)
    ] as Set)
  }

  @Override
  Collection<Map> pendingOnDemandRequests(ProviderCache providerCache) {
    def keys = providerCache.getIdentifiers(AZURE_ON_DEMAND.ns)
    keys = keys.findAll {
      def key = Keys.parse(azureCloudProvider, it)
      key.type == AZURE_SERVER_GROUPS.ns && key.account == accountName && key.region == region
    }
    return providerCache.getAll(AZURE_ON_DEMAND.ns, keys, RelationshipCacheFilter.none()).collect {
      [
        id: it.id,
        details  : Keys.parse(azureCloudProvider, it.id),
        cacheTime: it.attributes.cacheTime,
        processedCount: it.attributes.processedCount,
        processedTime: it.attributes.processedTime
      ]
    }
  }

  @Override
  OnDemandAgent.OnDemandResult handle(ProviderCache providerCache, Map<String, ? extends Object> data) {
    if (!validKeys(data)) {
      return null
    }

    AzureServerGroupDescription serverGroup = null
    String serverGroupName = data.serverGroupName as String
    String serverGroupKey = Keys.getServerGroupKey(AzureCloudProvider.AZURE, serverGroupName, region, accountName)
    String resourceGroupName = AzureUtilities.getResourceGroupName(AzureUtilities.getAppNameFromAzureResourceName(serverGroupName), region)
    if (resourceGroupName == null) {
      log.info("handle->Unexpected error retrieving resource group name: null")
      return []
    }

    try {
      serverGroup = metricsSupport.readData {
        creds.computeClient.getServerGroup(resourceGroupName, serverGroupName)
      }
    } catch (Exception e ) {
      log.error("handle->Unexpected exception: ${e.message}")
      return null
    }

    def cacheResult = metricsSupport.transformData {
      buildCacheResult(providerCache, [serverGroup], [:], [])
    }

    if (cacheResult.cacheResults.values().flatten().isEmpty()) {
      // If the server group is gone from Azure then remove it from the OnDemand Cache
      // and add it to the evicted cache so that the primary loop (loadData) will (hopefully) not
      // try to put it back
      providerCache.evictDeletedItems(AZURE_ON_DEMAND.ns, [serverGroupKey])
      def cacheData = new DefaultCacheData(serverGroupKey,
        10*60,
        [evictionTime     : System.currentTimeMillis()],
        [:]
      )

      providerCache.putCacheData(AZURE_EVICTIONS.ns, cacheData)
    }
    else {
      def cacheResultJson = objectMapper.writeValueAsString(cacheResult.cacheResults)
      metricsSupport.onDemandStore {
        def cacheData = new DefaultCacheData(
          serverGroupKey,
          10 * 60,
          [
            cacheTime     : serverGroup.lastReadTime,
            cacheResults  : cacheResultJson,
            processedCount: 0,
            processedTime : null
          ],
          [:]
        )
        providerCache.putCacheData(AZURE_ON_DEMAND.ns, cacheData)
      }
    }

    Map<String, Collection<String>> evictions = serverGroup  ? [:] : [(AZURE_SERVER_GROUPS.ns): [serverGroupKey]]

    log.info("onDemand cache refresh (data: ${data}, evictions: ${evictions})")
    return new OnDemandAgent.OnDemandResult(
      sourceAgentType: getAgentType(), cacheResult: cacheResult, evictions: evictions
    )
  }

  CacheResult buildCacheResult(ProviderCache providerCache, Collection<AzureServerGroupDescription> serverGroups,
                               Map<String, CacheData> onDemandCacheResults, List<String> evictions) {

      Map<String, MutableCacheData> cachedApplications = MutableCacheData.mutableCacheMap()
      Map<String, MutableCacheData> cachedClusters = MutableCacheData.mutableCacheMap()
      Map<String, MutableCacheData> cachedServerGroups = MutableCacheData.mutableCacheMap()
      Map<String, MutableCacheData> cachedInstances = MutableCacheData.mutableCacheMap()

      serverGroups.each { serverGroup ->

        // see if this server group is in the onDemand cache
        def serverGroupKey = Keys.getServerGroupKey(azureCloudProvider, serverGroup.name, region, accountName)

        def onDemandCacheData = onDemandCacheResults ? onDemandCacheResults[serverGroupKey] : null
        if (onDemandCacheData && onDemandCacheData.attributes.cachedTime > serverGroup.lastReadTime
          && !hasBeenEvicted(providerCache, serverGroupKey, serverGroup.lastReadTime)) {
          log.info("Using onDemand cache value (id: ${onDemandCacheData.id}, json: ${onDemandCacheData.attributes.cacheResults})")

          Map<String, List<CacheData>> results = objectMapper.readValue(onDemandCacheData.attributes.cacheResults as String,
            new TypeReference<Map<String, List<MutableCacheData>>>() {})

          cache(results[AZURE_APPLICATIONS.ns], cachedApplications)
          cache(results[AZURE_CLUSTERS.ns], cachedClusters)
          cache(results[AZURE_SERVER_GROUPS.ns], cachedServerGroups)
          cache(results[AZURE_INSTANCES.ns], cachedInstances)
        }
        else {

          def clusterKey = Keys.getClusterKey(azureCloudProvider, serverGroup.appName, serverGroup.clusterName, accountName)
          def appKey = Keys.getApplicationKey(azureCloudProvider, serverGroup.appName)
          def loadBalancerKey = Keys.getLoadBalancerKey(azureCloudProvider, serverGroup.loadBalancerName, serverGroup.loadBalancerName,
            serverGroup.application, serverGroup.clusterName, region, accountName)

          cachedApplications[appKey].with {
            attributes.name = serverGroup.appName
            relationships[AZURE_CLUSTERS.ns].add(clusterKey)
            relationships[AZURE_SERVER_GROUPS.ns].add(serverGroupKey)
            relationships[AZURE_LOAD_BALANCERS.ns].add(loadBalancerKey)
          }

          cachedClusters[clusterKey].with {
            attributes.name = serverGroup.clusterName
            attributes.accountName = accountName
            relationships[AZURE_APPLICATIONS.ns].add(appKey)
            relationships[AZURE_SERVER_GROUPS.ns].add(serverGroupKey)
            relationships[AZURE_LOAD_BALANCERS.ns].add(loadBalancerKey)
          }

          cachedServerGroups[serverGroupKey].with {
            attributes.serverGroup = serverGroup
            relationships[AZURE_APPLICATIONS.ns].add(appKey)
            relationships[AZURE_CLUSTERS.ns].add(clusterKey)
            relationships[AZURE_LOAD_BALANCERS.ns].add(loadBalancerKey)
          }

          creds.computeClient.getServerGroupInstances(AzureUtilities.getResourceGroupName(serverGroup), serverGroup.name).each { instance ->
            def instanceKey = Keys.getInstanceKey(azureCloudProvider, serverGroup.name, instance.name, region, accountName)
            cachedInstances[instanceKey].with {
              attributes.instance = instance
              relationships[AZURE_SERVER_GROUPS.ns].add(serverGroupKey)
            }
            cachedServerGroups[serverGroupKey].relationships[AZURE_INSTANCES.ns].add(instanceKey)
          }
        }
      }

    new DefaultCacheResult([
      (AZURE_APPLICATIONS.ns) : cachedApplications.values(),
      (AZURE_CLUSTERS.ns) : cachedClusters.values(),
      (AZURE_SERVER_GROUPS.ns) : cachedServerGroups.values(),
      (AZURE_INSTANCES.ns) : cachedInstances.values(),
      (AZURE_ON_DEMAND.ns) : onDemandCacheResults.values()
    ], [
      (AZURE_ON_DEMAND.ns): evictions as List<String>
    ])

  }

  @Override
  Boolean validKeys(Map<String, ? extends Object> data) {
    (data.containsKey("serverGroupName")
      && data.containsKey("account")
      && data.containsKey("region")
      && accountName == data.account
      && region == data.region)
  }

  @Override
  OnDemandAgent.OnDemandType getOnDemandType() {
    OnDemandAgent.OnDemandType.ServerGroup
  }

  private static void cache(List<CacheData> data, Map<String, CacheData> cacheDataById) {
    data.each {
      def existingCacheData = cacheDataById[it.id]
      if (!existingCacheData) {
        cacheDataById[it.id] = it
      } else {
        existingCacheData.attributes.putAll(it.attributes)
        it.relationships.each { String relationshipName, Collection<String> relationships ->
          existingCacheData.relationships[relationshipName].addAll(relationships)
        }
      }
    }
  }

  private static Boolean hasBeenEvicted(ProviderCache cache, String key, Long lastReadTime) {
    def eviction = cache.get(AZURE_EVICTIONS.ns, key)
    (eviction && eviction.attributes.evictionTime > lastReadTime)
  }

}
