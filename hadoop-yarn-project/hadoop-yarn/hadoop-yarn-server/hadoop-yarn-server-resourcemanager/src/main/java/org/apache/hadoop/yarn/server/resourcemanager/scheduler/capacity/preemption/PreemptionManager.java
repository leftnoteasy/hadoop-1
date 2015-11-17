/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.yarn.server.resourcemanager.scheduler.capacity.preemption;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.hadoop.yarn.api.records.ApplicationAttemptId;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.api.records.Priority;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.api.records.ResourceRequest;
import org.apache.hadoop.yarn.server.resourcemanager.rmcontainer.RMContainer;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.ResourceUsage;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.SchedulerApplicationAttempt;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.capacity.CSQueue;
import org.apache.hadoop.yarn.util.Clock;
import org.apache.hadoop.yarn.util.SystemClock;
import org.apache.hadoop.yarn.util.resource.ResourceCalculator;
import org.apache.hadoop.yarn.util.resource.Resources;

public class PreemptionManager {
  enum PreemptionType {
    // Currently only DIFFERENCE_QUEUE will be used.
    DIFFERENT_QUEUE, 
    SAME_QUEUE_DIFFERENT_USER, 
    SAME_QUEUE_SAME_USER
  }

  static class ToPreemptContainer {
    RMContainer container;
    ResourceRequirement resourceRequirement;
    long startTimestamp;
    long lastListedTimestamp = Long.MAX_VALUE;
    PreemptionType preemptionType;
    PreemptableEntityMeasure containerQueueMeasure;
    PreemptableEntityMeasure demandingQueueMeasure;

    public ToPreemptContainer(RMContainer container, long startTimestamp,
        PreemptionType preemptionType, ResourceRequirement resourceRequirement,
        PreemptableEntityMeasure containerQueue, PreemptableEntityMeasure demandingQueue) {
      this.container = container;
      this.startTimestamp = startTimestamp;
      this.preemptionType = preemptionType;
      this.resourceRequirement = resourceRequirement;
      this.containerQueueMeasure = containerQueue;
      this.demandingQueueMeasure = demandingQueue;
    }
  }

  /*
   * One preemptable entity, this could be a queue, a user or application.
   * User/Application will be used only when intra-queue preemption supported
   */
  static class PreemptableEntityMeasure {
    String entityKey;

    // PlaceHolder: String parentKey;
    // PlaceHolder: String user;
    // PlaceHolder: String appId;

    Resource ideal;
    
    // When this is debtor (debtor == true), maxPreemptable means how much
    // resource needs to be taken from this queue. 
    // When this is not debtor, maxPreemptable means how much resource this
    // queue need to get.
    Resource maxPreemptable;
    Resource totalMarkedPreempted;
    
    // If someone should preempt resource from this entity
    boolean debtor = false;
    
    private Resource totalMarkedPreemptedForDryRun;
    private long timestamp;

    public PreemptableEntityMeasure(String entityKey) {
      this.entityKey = entityKey;
      this.totalMarkedPreempted = Resources.createResource(0);
    }
    
    public Resource getTotalMarkedPreemptedForDryRun(long timestamp) {
      if (this.timestamp != timestamp) {
        totalMarkedPreemptedForDryRun = Resources.clone(totalMarkedPreempted);
        this.timestamp = timestamp;
      }
      return totalMarkedPreemptedForDryRun;
    }
  }

  class PreemptableEntitiesManager {
    private Map<String, PreemptableEntityMeasure> map = new HashMap<>();

    PreemptableEntityMeasure getOrAddNew(String key) {
      PreemptableEntityMeasure measure = map.get(key);
      if (measure == null) {
        measure = new PreemptableEntityMeasure(key);
        map.put(key, measure);
      }
      return measure;
    }

    PreemptableEntityMeasure get(String key) {
      return map.get(key);
    }

    public void updatePreemptableQueueEntity(String queue, String partition,
        Resource ideal, Resource maxPreempt) {
      String key = queue + "_" + partition;

      PreemptableEntityMeasure measure = getOrAddNew(key);
      measure.ideal = ideal;

      // When maxPreempt is positive, it means this entity is a debtor
      if (maxPreempt.getMemory() + maxPreempt.getVirtualCores() > 0) {
        if (!measure.debtor) {
          // The queue becomes a debtor, if there's any container will be
          // preempted by apps belong to this queue, unmark them.
          List<ContainerId> unmarkContainers = new ArrayList<>();
          for (ToPreemptContainer c : preemptionReleationshipManager.toPreemptContainers
              .values()) {
            if (c.resourceRequirement.application.getQueueName()
                .equals(queue)) {
              unmarkContainers.add(c.container.getContainerId());
            }
          }

          for (ContainerId id : unmarkContainers) {
            preemptionReleationshipManager.unmarkToPreemptContainer(id);
          }
        }

        measure.maxPreemptable = maxPreempt;
        measure.debtor = true;
      } else {
        // The queue becomes a non-debtor, if there's any container will be
        // preempted by apps belong to this queue, unmark them.
        List<ContainerId> unmarkContainers = new ArrayList<>();
        for (ToPreemptContainer c : preemptionReleationshipManager.toPreemptContainers
            .values()) {
          if (c.container.getQueue().equals(queue)) {
            unmarkContainers.add(c.container.getContainerId());
          }
        }

        for (ContainerId id : unmarkContainers) {
          preemptionReleationshipManager.unmarkToPreemptContainer(id);
        }

        measure.maxPreemptable = Resources.negate(maxPreempt);
        measure.debtor = false;
      }
    }
  }

  static class DemandingApp {
    ApplicationAttemptId appAttemptId;
    SchedulerApplicationAttempt application;
    // to-preempt containers for this app only
    Map<ContainerId, ToPreemptContainer> toPreemptContainers;
    // to-preempt resources, priority -> <resource-name,
    // -- how-much-resource-marked-to-be-preempted-from-other-applications>
    Map<Priority, Map<String, Resource>> toPreemptResources;
    // container to reference of how much resource marked to be preemption
    // classified by priority and resourceName (the reference of resource in
    // above map)
    Map<ContainerId, Resource> containerIdToToPreemptResource;
    
    public DemandingApp(ApplicationAttemptId appAttemptId,
        SchedulerApplicationAttempt application) {
      this.appAttemptId = appAttemptId;
      this.application = application;
      
      toPreemptContainers = new HashMap<>();
      toPreemptResources = new HashMap<>();
    }
    
    private Resource getOrAddNewResource(Priority priority, String resourceName) {
      if (!toPreemptResources.containsKey(priority)) {
        toPreemptResources.put(priority, new HashMap<String, Resource>());
      }
      if (!toPreemptResources.get(priority).containsKey(resourceName)) {
        toPreemptResources.get(priority).put(resourceName,
            Resources.createResource(0));
      }
      
      return toPreemptResources.get(priority).get(resourceName);
    }
    
    void addToPreemptContainer(ToPreemptContainer container, Priority priority,
        String resourceName) {
      ContainerId containerId = container.container.getContainerId();
      
      toPreemptContainers.put(containerId, container);
      
      if (!resourceName.equals(ResourceRequest.ANY)) {
        Resource resource = getOrAddNewResource(priority, resourceName);
        containerIdToToPreemptResource.put(containerId, resource);
        Resources.addTo(resource, container.container.getAllocatedResource());
      }
      
      // We will update ANY resource for all to-preempted container
      Resource resource = getOrAddNewResource(priority, ResourceRequest.ANY);
      Resources.addTo(resource, container.container.getAllocatedResource());
    }
    
    void unmarkToPreemptContainer(ContainerId containerId) {
      if (toPreemptContainers.containsKey(containerId)) {
        ToPreemptContainer container =
            toPreemptContainers.remove(containerId);

        // Only demanding resource request's resourceName != ANY needs to deduct
        // resource
        if (containerIdToToPreemptResource.containsKey(containerId)) {
          Resources.subtractFrom(
              containerIdToToPreemptResource
                  .get(container.container.getContainerId()),
              container.container.getAllocatedResource());
        }

        // Update ANY resource for the to-preempted container
        Resource resource = toPreemptResources.get(container.resourceRequirement.priority)
            .get(ResourceRequest.ANY);
        Resources.subtractFrom(resource,
            container.container.getAllocatedResource());
      }
    }
  }

  class PreemptionRelationshipManager {
    Map<ContainerId, ToPreemptContainer> toPreemptContainers = new HashMap<>();
    Map<ApplicationAttemptId, DemandingApp> demandingApps = new HashMap<>();

    void addToPreemptContainer(ToPreemptContainer container,
        ResourceRequirement resourceRequirement) {
      toPreemptContainers.put(container.container.getContainerId(), container);

      // Add or create demanding app if necessary
      ApplicationAttemptId attemptId =
          resourceRequirement.application.getApplicationAttemptId();
      if (!demandingApps.containsKey(attemptId)) {
        demandingApps.put(attemptId,
            new DemandingApp(attemptId, resourceRequirement.application));
      }
      DemandingApp demandingApp = demandingApps.get(attemptId);
      demandingApp.addToPreemptContainer(container,
          resourceRequirement.priority, resourceRequirement.resourceName);

      // updated marked-preempted resource
      Resource res = container.container.getAllocatedResource();
      Resources.addTo(container.containerQueueMeasure.totalMarkedPreempted,
          res);
      Resources.addTo(container.demandingQueueMeasure.totalMarkedPreempted,
          res);
    }

    void unmarkToPreemptContainer(ContainerId containerId) {
      if (toPreemptContainers.containsKey(containerId)) {
        ToPreemptContainer container =
            toPreemptContainers.remove(containerId);
        DemandingApp app =
            demandingApps.get(container.resourceRequirement.application
                .getApplicationAttemptId());
        if (app != null) {
          app.unmarkToPreemptContainer(containerId);
          // updated marked-preempted resource
          Resource res = container.container.getAllocatedResource();
          Resources.subtractFrom(
              container.containerQueueMeasure.totalMarkedPreempted, res);
          Resources.subtractFrom(
              container.demandingQueueMeasure.totalMarkedPreempted, res);
        }
      }
    }

    void unmarkDemandingApp(ApplicationAttemptId appAttemptId) {
      if (demandingApps.containsKey(appAttemptId)) {
        DemandingApp demandingApp = demandingApps.remove(appAttemptId);
        Set<ContainerId> containersRequiredByDemandingApp =
            demandingApp.toPreemptContainers.keySet();
        for (ContainerId id : containersRequiredByDemandingApp) {
          ToPreemptContainer container = toPreemptContainers.remove(id);
          if (null != container) {
            // updated marked-preempted resource
            Resource res = container.container.getAllocatedResource();
            Resources.subtractFrom(
                container.containerQueueMeasure.totalMarkedPreempted, res);
            Resources.subtractFrom(
                container.demandingQueueMeasure.totalMarkedPreempted, res);
          }
        }
      }
    }
  }
  
  ResourceCalculator rc;
  PreemptableEntitiesManager preemptableEntitiesManager =
      new PreemptableEntitiesManager();
  PreemptionRelationshipManager preemptionReleationshipManager =
      new PreemptionRelationshipManager();
  // selecting container for this preemption cycle, this will be cleared at
  // beginning of every preemption cycle.
  Set<ContainerId> selectingContainers = new HashSet<>();
  
  // To-be-killed containers
  Set<ContainerId> toKillContainers = new HashSet<>();

  // ResourceUsages
  Map<String, ResourceUsage> queueResourceUsages = new HashMap<>();
  
  Clock clock = new SystemClock();
  ReentrantReadWriteLock.ReadLock readLock;
  ReentrantReadWriteLock.WriteLock writeLock;
  
  // TODO change this to configurable
  private final static int WAIT_BEFORE_KILL_SEC = 30;
  
  public PreemptionManager() {
    ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    readLock = lock.readLock();
    writeLock = lock.writeLock();
  }

  public void init(ResourceCalculator rc) {
    this.rc = rc;
  }

  private boolean canPreempt(Resource cluster, Resource markedPreempted,
      Resource maxPreemptable, Resource current, Resource ideal,
      Resource newCandidate) {
    Resource totalMarkedPreemptedResourceIfSelected =
        Resources.add(markedPreempted, newCandidate);

    if (Resources.fitsIn(rc, cluster, totalMarkedPreemptedResourceIfSelected,
        maxPreemptable) || Resources
        .equals(markedPreempted, Resources.none())) {
      // We allow total-marked-to-be-preempted resource less than max-preemptable
      // OR one container
      if (Resources.fitsIn(rc, cluster, totalMarkedPreemptedResourceIfSelected,
          Resources.subtract(current, ideal))) {
        // In addition, total-marked-preempted should <= current - ideal
        return true;
      }
    }

    return false;
  }
  
  private List<RMContainer> selectContainersToPreempt(List<RMContainer> candidates,
      Resource required, Resource cluster, String nodePartition) {
    long timestamp = System.nanoTime();
    
    // Assume candidates is sorted by preemption order, first items will be preempted first.
    // Scan the list to select which containers to be preempted.
    Resource totalSelected = Resources.createResource(0);
    List<RMContainer> selected = new ArrayList<>();
    
    for (RMContainer candidateContainer : candidates) {
      // Skip all AM containers OR already selected containers
      if (candidateContainer.isAMContainer() || selectingContainers
          .contains(candidateContainer.getContainerId())) {
        continue;
      }
      
      String key = candidateContainer.getQueue() + "_" + nodePartition;
      PreemptableEntityMeasure measure = preemptableEntitiesManager.get(key);
      if (measure == null || !measure.debtor) {
        // Skip non-existed queue or non-debtor queue.
        continue;
      }
      
      Resource markedPreempted =
          measure.getTotalMarkedPreemptedForDryRun(timestamp);
      
      // We get enough preemption headroom for the candidate
      ResourceUsage queueResourceUsage =
          queueResourceUsages.get(candidateContainer.getQueue());
      if (canPreempt(cluster, markedPreempted, measure.maxPreemptable,
          queueResourceUsage.getUsed(nodePartition), measure.ideal,
          candidateContainer.getAllocatedResource())) {
        Resources
            .addTo(markedPreempted, candidateContainer.getAllocatedResource());
        selected.add(candidateContainer);
      }
      
      // update total resource as well
      Resources.addTo(totalSelected, candidateContainer.getAllocatedResource());
      if (Resources.fitsIn(rc, cluster, required, totalSelected)) {
        return selected;
      }
    }
    
    return null;
  }
  
  public Resource getTotalResourcesWillBePreemptedByApp(
      ApplicationAttemptId attemptId, Priority priority, String resourceName) {
    if (!preemptionReleationshipManager.demandingApps.containsKey(attemptId)) {
      return Resources.none();
    }

    DemandingApp app =
        preemptionReleationshipManager.demandingApps.get(attemptId);
    if (!app.toPreemptResources.containsKey(priority)) {
      return Resources.none();
    }

    Resource res = app.toPreemptResources.get(priority).get(resourceName);
    return res != null ? res : Resources.none();
  }
  
  public boolean canQueueuPreemptResourceFromOthers(Resource cluster,
      String queue, String partition, Resource demandingResource) {
    String key = queue + "_" + partition;
    PreemptableEntityMeasure measure = preemptableEntitiesManager.get(key);
    if (measure != null) {
      if (measure.debtor) {
        // Obviously, debtor cannot preempt resource from others
        return false;
      }
      
      Resource headroom = Resources.subtract(measure.maxPreemptable,
          measure.totalMarkedPreempted);
      if (Resources.fitsIn(rc, cluster, demandingResource, headroom)) {
        return true;
      }
    }
    
    return false;
  }
  
  @SuppressWarnings("unchecked")
  public Set<ContainerId> pullContainersToKill() {
    try {
      writeLock.lock();

      if (toKillContainers.isEmpty()) {
        return Collections.EMPTY_SET;
      }
      Set<ContainerId> ret = toKillContainers;
      toKillContainers = new HashSet<>();
      return ret;
    } finally {
      writeLock.unlock();
    }
  }

  public boolean tryToPreempt(ResourceRequirement resourceRequirement,
      Collection<RMContainer> candidatesToPreempt, Resource cluster,
      String nodePartition) {
    selectingContainers.clear();
    
    List<RMContainer> candidates = getContainers(PreemptionType.DIFFERENT_QUEUE,
        resourceRequirement, candidatesToPreempt);
    
    List<RMContainer> selectedContainers = selectContainersToPreempt(candidates,
        resourceRequirement.getRequired(), cluster, nodePartition);
    
    PreemptableEntityMeasure demandingQueueEntity = null;
    
    if (selectedContainers != null) {
      // Updates container preemption info
      for (RMContainer c : selectedContainers) {
        String key = c.getQueue() + "_" + nodePartition;
        PreemptableEntityMeasure containerQueueMeasure =
            preemptableEntitiesManager.get(key);
        if (demandingQueueEntity == null) {
          demandingQueueEntity = preemptableEntitiesManager
              .getOrAddNew(resourceRequirement.application.getQueueName() + "_"
                  + nodePartition);
        }

        // create ToPreempt container if necessary
        ToPreemptContainer toPreemptContainer =
            preemptionReleationshipManager.toPreemptContainers
                .get(c.getContainerId());
        
        if (toPreemptContainer != null) {
          // If this container is already on our to-preempt containers list...
          
          if (!toPreemptContainer.resourceRequirement
              .equals(resourceRequirement)) {
            // If this container will be used by a different resource
            // requirement, we will cancel the previous one
            preemptionReleationshipManager
                .unmarkToPreemptContainer(c.getContainerId());
            
            // Create a new ToPreemptContainer, the new ToPreempt container will
            // inherit previous startTime so we don't need to wait another
            // timeout to kill the container
            ToPreemptContainer newToPreemptContainer =
                new ToPreemptContainer(c, toPreemptContainer.startTimestamp,
                    PreemptionType.DIFFERENT_QUEUE, resourceRequirement,
                    containerQueueMeasure, demandingQueueEntity);
            preemptionReleationshipManager.addToPreemptContainer(
                newToPreemptContainer, resourceRequirement);
          } else {
            long currentTime = clock.getTime();
            
            if (currentTime
                - toPreemptContainer.startTimestamp > WAIT_BEFORE_KILL_SEC) {
              toKillContainers.add(c.getContainerId());
            }
            
            // Update last listed timestamp for this container, this will be
            // used to decide if a ToPreemptContainer could be removed from list
            toPreemptContainer.lastListedTimestamp = currentTime;
          }
        } else {
          // Create a new ToPreemptContainer
          ToPreemptContainer newToPreemptContainer = new ToPreemptContainer(c,
              clock.getTime(), PreemptionType.DIFFERENT_QUEUE,
              resourceRequirement, containerQueueMeasure, demandingQueueEntity);
          preemptionReleationshipManager.addToPreemptContainer(
              newToPreemptContainer, resourceRequirement); 
        }
      }
      
      return true;
    }
    
    return false;
  }
  
  public void updatePreemptableQueuePartitions(
      Collection<PreemptableQueuePartitionEntity> entities) {
    try {
      writeLock.lock();
      for (PreemptableQueuePartitionEntity entity : entities) {
        preemptableEntitiesManager.updatePreemptableQueueEntity(
            entity.getQueueName(), entity.getPartitionName(), entity.getIdeal(),
            entity.getPreemptable());
      }
    } finally {
      writeLock.unlock();
    }
  }
  
  
  public void unmarkToPreemptContainer(ContainerId containerId) {
    try {
      writeLock.lock();
      preemptionReleationshipManager.unmarkToPreemptContainer(containerId);
    } finally{
      writeLock.unlock();
    }
  }

  public void unmarkDemandingApp(ApplicationAttemptId attempId) {
    try {
      writeLock.lock();
      preemptionReleationshipManager.unmarkDemandingApp(attempId);
    } finally{
      writeLock.unlock();
    }
  }

  private void updateResourceUsages(CSQueue root) {
    queueResourceUsages.clear();

    Queue<CSQueue> bfsQueue = new LinkedList<>();
    bfsQueue.offer(root);
    while (!bfsQueue.isEmpty()) {
      CSQueue cur = bfsQueue.remove();

      if (null == cur.getChildQueues() || cur.getChildQueues().isEmpty()) {
        // add ResourceUsage of leaf queues to map
        queueResourceUsages.put(cur.getQueueName(), cur.getQueueResourceUsage());
      } else {
        // add children to bfs queue
        for (CSQueue q : cur.getChildQueues()) {
          bfsQueue.offer(q);
        }
      }
    }
  }

  public void queueRefreshed(CSQueue root) {
    try {
      writeLock.lock();
      updateResourceUsages(root);
    } finally {
      writeLock.unlock();
    }
  }

  PreemptionType getPreemptionType(ResourceRequirement requirement,
      RMContainer preemptionCandidate) {
    if (!requirement.getApplication().getQueue()
        .equals(preemptionCandidate.getQueue())) {
      return PreemptionType.DIFFERENT_QUEUE;
    } else if (!requirement.getApplication().getUser()
        .equals(preemptionCandidate.getUser())) {
      return PreemptionType.SAME_QUEUE_DIFFERENT_USER;
    } else {
      return PreemptionType.SAME_QUEUE_SAME_USER;
    }
  }

  List<RMContainer> getContainers(PreemptionType preemptionType,
      ResourceRequirement resourceRequirement,
      Collection<RMContainer> candidates) {
    List<RMContainer> containers = new ArrayList<>();
    for (RMContainer container : candidates) {
      if (getPreemptionType(resourceRequirement, container) == preemptionType) {
        containers.add(container);
      }
    }

    return containers;
  }
}
