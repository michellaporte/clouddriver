/*
 * Copyright 2015 Netflix, Inc.
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

package com.netflix.spinnaker.mort.aws.provider.view

import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.spinnaker.cats.cache.Cache
import com.netflix.spinnaker.cats.cache.CacheData
import com.netflix.spinnaker.cats.cache.RelationshipCacheFilter
import com.netflix.spinnaker.clouddriver.aws.AmazonCloudProvider
import com.netflix.spinnaker.mort.aws.cache.Keys
import com.netflix.spinnaker.mort.aws.model.AmazonElasticIp
import com.netflix.spinnaker.mort.model.ElasticIpProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

import static com.netflix.spinnaker.mort.aws.cache.Keys.Namespace.ELASTIC_IPS

@Component
class AmazonElasticIpProvider implements ElasticIpProvider<AmazonElasticIp> {

  private final AmazonCloudProvider amazonCloudProvider
  private final Cache cacheView
  private final ObjectMapper objectMapper

  @Autowired
  AmazonElasticIpProvider(AmazonCloudProvider amazonCloudProvider, Cache cacheView, ObjectMapper objectMapper) {
    this.amazonCloudProvider = amazonCloudProvider
    this.cacheView = cacheView
    this.objectMapper = objectMapper
  }

  @Override
  Set<AmazonElasticIp> getAllByAccount(String account) {
    loadResults(cacheView.filterIdentifiers(ELASTIC_IPS.ns, Keys.getElasticIpKey(amazonCloudProvider, '*', '*', account)))
  }

  @Override
  Set<AmazonElasticIp> getAllByAccountAndRegion(String account, String region) {
    loadResults(cacheView.filterIdentifiers(ELASTIC_IPS.ns, Keys.getElasticIpKey(amazonCloudProvider, '*', region, account)))
  }

  Set<AmazonElasticIp> loadResults(Collection<String> identifiers) {
    cacheView.getAll(ELASTIC_IPS.ns, identifiers, RelationshipCacheFilter.none()).collect { CacheData data ->
      objectMapper.convertValue(data.attributes, AmazonElasticIp)
    }
  }
}
