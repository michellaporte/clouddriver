/*
 * Copyright 2014 Netflix, Inc.
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

package com.netflix.spinnaker.clouddriver.controllers

import com.netflix.spinnaker.clouddriver.model.Application
import com.netflix.spinnaker.clouddriver.model.ApplicationProvider
import com.netflix.spinnaker.clouddriver.model.Cluster
import com.netflix.spinnaker.clouddriver.model.ClusterProvider
import com.netflix.spinnaker.clouddriver.model.ServerGroup
import com.netflix.spinnaker.clouddriver.model.Summary
import com.netflix.spinnaker.clouddriver.model.TargetServerGroup
import com.netflix.spinnaker.clouddriver.requestqueue.RequestQueue
import com.netflix.spinnaker.kork.web.exceptions.NotFoundException
import groovy.transform.Canonical
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.MessageSource
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PostAuthorize
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/applications/{application}/clusters")
class ClusterController {

  @Autowired
  List<ApplicationProvider> applicationProviders

  @Autowired
  List<ClusterProvider> clusterProviders

  @Autowired
  MessageSource messageSource

  @Autowired
  RequestQueue requestQueue

  @Autowired
  ServerGroupController serverGroupController

  @PreAuthorize("@fiatPermissionEvaluator.storeWholePermission() and hasPermission(#application, 'APPLICATION', 'READ')")
  @PostAuthorize("@authorizationSupport.filterForAccounts(returnObject)")
  @RequestMapping(method = RequestMethod.GET)
  Map<String, Set<String>> listByAccount(@PathVariable String application) {
    def apps = ((List<Application>) applicationProviders.collectMany {
      [it.getApplication(application)] ?: []
    }).findAll().sort { a, b -> a.name.toLowerCase() <=> b.name.toLowerCase() }
    def clusterNames = [:]
    def lastApp = null
    for (app in apps) {
      if (!lastApp) {
        clusterNames = app.clusterNames
      } else {
        clusterNames = Application.mergeClusters.curry(lastApp, app).call()
      }
      lastApp = app
    }
    clusterNames
  }

  @PreAuthorize("hasPermission(#application, 'APPLICATION', 'READ') && hasPermission(#account, 'ACCOUNT', 'READ')")
  @RequestMapping(value = "/{account:.+}", method = RequestMethod.GET)
  Set<ClusterViewModel> getForAccount(@PathVariable String application, @PathVariable String account) {
    def clusters = clusterProviders.collect {
      def clusters = (Set<Cluster>) it.getClusters(application, account)
      def clusterViews = []
      for (cluster in clusters) {
        clusterViews << new ClusterViewModel(name: cluster.name, account: cluster.accountName, loadBalancers: cluster.loadBalancers.collect {
          it.name
        }, serverGroups: cluster.serverGroups.collect {
          it.name
        })
      }
      clusterViews
    }?.flatten() as Set<ClusterViewModel>
    if (!clusters) {
      throw new NotFoundException("No clusters found (application: ${application}, account: ${account})")
    }
    clusters
  }

  @PreAuthorize("hasPermission(#application, 'APPLICATION', 'READ') && hasPermission(#account, 'ACCOUNT', 'READ')")
  @RequestMapping(value = "/{account:.+}/{name:.+}", method = RequestMethod.GET)
  Set<Cluster> getForAccountAndName(@PathVariable String application,
                                    @PathVariable String account,
                                    @PathVariable String name,
                                    @RequestParam(required = false, value = 'expand', defaultValue = 'true') boolean expand) {
    def clusters = clusterProviders.collect { provider ->
      requestQueue.execute(application, { provider.getCluster(application, account, name, expand) })
    }

    clusters.removeAll([null])
    if (!clusters) {
      throw new NotFoundException("Cluster not found (application: ${application}, account: ${account}, name: ${name})")
    }
    clusters
  }

  @PreAuthorize("hasPermission(#application, 'APPLICATION', 'READ') && hasPermission(#account, 'ACCOUNT', 'READ')")
  @RequestMapping(value = "/{account:.+}/{name:.+}/{type}", method = RequestMethod.GET)
  Cluster getForAccountAndNameAndType(@PathVariable String application,
                                      @PathVariable String account,
                                      @PathVariable String name,
                                      @PathVariable String type,
                                      @RequestParam(required = false, value = 'expand', defaultValue = 'true') boolean expand) {
    Set<Cluster> allClusters = getForAccountAndName(application, account, name, expand)
    def cluster = allClusters.find { it.type == type }
    if (!cluster) {
      throw new NotFoundException("No clusters found (application: ${application}, account: ${account}, type: ${type})")
    }
    cluster
  }

  @PreAuthorize("hasPermission(#application, 'APPLICATION', 'READ') && hasPermission(#account, 'ACCOUNT', 'READ')")
  @RequestMapping(value = "/{account:.+}/{clusterName:.+}/{type}/serverGroups", method = RequestMethod.GET)
  Set<ServerGroup> getServerGroups(@PathVariable String application,
                                   @PathVariable String account,
                                   @PathVariable String clusterName,
                                   @PathVariable String type,
                                   @RequestParam(value = "region", required = false) String region,
                                   @RequestParam(required = false, value = 'expand', defaultValue = 'true') boolean expand) {
    Cluster cluster = getForAccountAndNameAndType(application, account, clusterName, type, expand)
    def results = region ? cluster.serverGroups.findAll { it.region == region } : cluster.serverGroups
    results ?: []
  }

  @PreAuthorize("hasPermission(#application, 'APPLICATION', 'READ') && hasPermission(#account, 'ACCOUNT', 'READ')")
  @RequestMapping(value = "/{account:.+}/{clusterName:.+}/{type}/serverGroups/{serverGroupName:.+}", method = RequestMethod.GET)
  def getServerGroup(@PathVariable String application,
                     @PathVariable String account,
                     @PathVariable String clusterName,
                     @PathVariable String type,
                     @PathVariable String serverGroupName,
                     @RequestParam(value = "region", required = false) String region) {
    def notFound = new NotFoundException("Server group not found (account: ${account}, name: ${serverGroupName}, type: ${type})")

    if (!serverGroupName.toLowerCase().startsWith(clusterName.toLowerCase())) {
      throw notFound
    }

    if (region) {
      // if region is supplied,
      def serverGroup = serverGroupController.getServerGroup(application, account, region, serverGroupName)
      if (serverGroup.getCloudProvider() != type) {
        throw notFound
      }

      return serverGroup
    }

    // load all server groups w/o instance details (this is reasonably efficient)
    def serverGroups = getServerGroups(application, account, clusterName, type, region, false).findAll {
      it.name == serverGroupName
    }

    if (!serverGroups) {
      throw notFound
    }

    // load matched server groups w/ instance details
    return serverGroups.collect {
      serverGroupController.getServerGroup(application, account, it.region, it.name)
    }
  }

  /**
   * @param scope Should be either a region or zone, depending on the cloud provider.
   * @return A dynamically determined server group using a {@code TargetServerGroup} specifier.
   */
  @PreAuthorize("hasPermission(#application, 'APPLICATION', 'READ') && hasPermission(#account, 'ACCOUNT', 'READ')")
  @RequestMapping(value = "/{account:.+}/{clusterName:.+}/{cloudProvider}/{scope}/serverGroups/target/{target:.+}", method = RequestMethod.GET)
  ServerGroup getTargetServerGroup(
      @PathVariable String application,
      @PathVariable String account,
      @PathVariable String clusterName,
      @PathVariable String cloudProvider,
      @PathVariable String scope,
      @PathVariable String target,
      @RequestParam(value = "onlyEnabled", required = false, defaultValue = "false") String onlyEnabled,
      @RequestParam(value = "validateOldest", required = false, defaultValue = "true") String validateOldest) {
    TargetServerGroup tsg
    try {
      tsg = TargetServerGroup.fromString(target)
    } catch (IllegalArgumentException e) {
      throw new NotFoundException("Target not found (target: ${target})")
    }

    // load all server groups w/o instance details (this is reasonably efficient)
    def sortedServerGroups = getServerGroups(application, account, clusterName, cloudProvider, null /* region */, false).findAll {
      def scopeMatch = it.region == scope || it.zones.contains(scope)

      def enableMatch
      if (Boolean.valueOf(onlyEnabled)) {
        enableMatch = !it.isDisabled()
      } else {
        enableMatch = true
      }

      return scopeMatch && enableMatch
    }.sort { a, b -> b.createdTime <=> a.createdTime }

    if (!sortedServerGroups) {
      throw new NotFoundException("No server groups found (account: ${account}, cluster: ${clusterName}, type: ${cloudProvider})")
    }

    // load matched server groups w/ instance details
    sortedServerGroups = sortedServerGroups.collect {
      serverGroupController.getServerGroup(application, account, it.region, it.name)
    }

    switch (tsg) {
      case TargetServerGroup.CURRENT:
        return sortedServerGroups.get(0)
      case TargetServerGroup.PREVIOUS:
        if (sortedServerGroups.size() == 1) {
          throw new NotFoundException("Target not found (target: ${target})")
        }
        return sortedServerGroups.get(1)
      case TargetServerGroup.OLDEST:
        // At least two expected, but some cases just want the oldest no matter what.
        if (Boolean.valueOf(validateOldest) && sortedServerGroups.size() == 1) {
          throw new NotFoundException("Target not found (target: ${target})")
        }
        return sortedServerGroups.last()
      case TargetServerGroup.LARGEST:
        // Choose the server group with the most instances, falling back to newest in the case of a tie.
        return sortedServerGroups.sort { lhs, rhs ->
          rhs.instances.size() <=> lhs.instances.size() ?:
              rhs.createdTime <=> lhs.createdTime
        }.get(0)
      case TargetServerGroup.FAIL:
        if (sortedServerGroups.size() > 1) {
          throw new NotFoundException("More than one target found (scope: ${scope}, serverGroups: ${sortedServerGroups*.name})")
        }
        return sortedServerGroups.get(0)
    }
  }

  @PreAuthorize("hasPermission(#application, 'APPLICATION', 'READ') && hasPermission(#account, 'ACCOUNT', 'READ')")
  @RequestMapping(value = "/{account:.+}/{clusterName:.+}/{cloudProvider}/{scope}/serverGroups/target/{target:.+}/{summaryType:.+}", method = RequestMethod.GET)
  Summary getServerGroupSummary(
      @PathVariable String application,
      @PathVariable String account,
      @PathVariable String clusterName,
      @PathVariable String cloudProvider,
      @PathVariable String scope,
      @PathVariable String target,
      @PathVariable String summaryType,
      @RequestParam(value = "onlyEnabled", required = false, defaultValue = "false") String onlyEnabled) {
    ServerGroup sg = getTargetServerGroup(application,
        account,
        clusterName,
        cloudProvider,
        scope,
        target,
        onlyEnabled,
        "false" /* validateOldest */)
    try {
      return (Summary) sg.invokeMethod("get${summaryType.capitalize()}Summary".toString(), null /* args */)
    } catch (MissingMethodException e) {
      throw new NotFoundException("Summary not found (type: ${summaryType})")
    }
  }

  @Canonical
  static class ClusterViewModel {
    String name
    String account
    List<String> loadBalancers
    List<String> serverGroups
  }
}
