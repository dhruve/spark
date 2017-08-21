/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark

import scala.collection.mutable

import org.scalatest.{BeforeAndAfter, PrivateMethodTester}

import org.apache.spark.executor.TaskMetrics
import org.apache.spark.scheduler._
import org.apache.spark.scheduler.ExternalClusterManager
import org.apache.spark.scheduler.cluster.ExecutorInfo
import org.apache.spark.scheduler.local.LocalSchedulerBackend
import org.apache.spark.util.ManualClock

/**
 * Test add and remove behavior of ExecutorAllocationManager.
 */
class ExecutorAllocationManagerSuite
  extends SparkFunSuite
  with LocalSparkContext
  with BeforeAndAfter {

  import ExecutorAllocationManager._
  import ExecutorAllocationManagerSuite._

  private val contexts = new mutable.ListBuffer[SparkContext]()

  before {
    contexts.clear()
  }

  after {
    contexts.foreach(_.stop())
  }

  test("verify min/max executors") {
    val conf = new SparkConf()
      .setMaster("myDummyLocalExternalClusterManager")
      .setAppName("test-executor-allocation-manager")
      .set("spark.dynamicAllocation.enabled", "true")
      .set("spark.dynamicAllocation.testing", "true")
    val sc0 = new SparkContext(conf)
    contexts += sc0
    assert(sc0.executorAllocationManager.isDefined)
    sc0.stop()

    // Min < 0
    val conf1 = conf.clone().set("spark.dynamicAllocation.minExecutors", "-1")
    intercept[SparkException] { contexts += new SparkContext(conf1) }

    // Max < 0
    val conf2 = conf.clone().set("spark.dynamicAllocation.maxExecutors", "-1")
    intercept[SparkException] { contexts += new SparkContext(conf2) }

    // Both min and max, but min > max
    intercept[SparkException] { createSparkContext(2, 1) }

    // Both min and max, and min == max
    val sc1 = createSparkContext(1, 1)
    assert(sc1.executorAllocationManager.isDefined)
    sc1.stop()

    // Both min and max, and min < max
    val sc2 = createSparkContext(1, 2)
    assert(sc2.executorAllocationManager.isDefined)
    sc2.stop()
  }

  test("starting state") {
    sc = createSparkContext()
    val manager = sc.executorAllocationManager.get
    assert(numExecutorsTarget(manager) === 1)
    assert(executorsPendingToRemove(manager).isEmpty)
    assert(executorIds(manager).isEmpty)
    assert(addTime(manager) === ExecutorAllocationManager.NOT_SET)
    assert(removeTimes(manager).isEmpty)
  }

  test("add executors") {
    sc = createSparkContext(1, 10, 1)
    val manager = sc.executorAllocationManager.get
    val stage0 = createStageInfo(0, 1000)
    sc.listenerBus.postToAll(SparkListenerJobStart(0, 0, Seq(stage0), sc.getLocalProperties))
    sc.listenerBus.postToAll(SparkListenerStageSubmitted(stage0))

    // Keep adding until the limit is reached
    assert(numExecutorsTarget(manager) === 1)
    assert(numExecutorsToAdd(manager) === 1)
    assert(addExecutors(manager) === 1)
    assert(numExecutorsTarget(manager) === 2)
    assert(numExecutorsToAdd(manager) === 2)
    assert(addExecutors(manager) === 2)
    assert(numExecutorsTarget(manager) === 4)
    assert(numExecutorsToAdd(manager) === 4)
    assert(addExecutors(manager) === 4)
    assert(numExecutorsTarget(manager) === 8)
    assert(numExecutorsToAdd(manager) === 8)
    assert(addExecutors(manager) === 2) // reached the limit of 10
    assert(numExecutorsTarget(manager) === 10)
    assert(numExecutorsToAdd(manager) === 1)
    assert(addExecutors(manager) === 0)
    assert(numExecutorsTarget(manager) === 10)
    assert(numExecutorsToAdd(manager) === 1)

    // Register previously requested executors
    onExecutorAdded(manager, "first")
    assert(numExecutorsTarget(manager) === 10)
    onExecutorAdded(manager, "second")
    onExecutorAdded(manager, "third")
    onExecutorAdded(manager, "fourth")
    assert(numExecutorsTarget(manager) === 10)
    onExecutorAdded(manager, "first") // duplicates should not count
    onExecutorAdded(manager, "second")
    assert(numExecutorsTarget(manager) === 10)

    // Try adding again
    // This should still fail because the number pending + running is still at the limit
    assert(addExecutors(manager) === 0)
    assert(numExecutorsTarget(manager) === 10)
    assert(numExecutorsToAdd(manager) === 1)
    assert(addExecutors(manager) === 0)
    assert(numExecutorsTarget(manager) === 10)
    assert(numExecutorsToAdd(manager) === 1)
  }

  test("add executors capped by num pending tasks") {
    sc = createSparkContext(0, 10, 0)
    val manager = sc.executorAllocationManager.get
    val stages = Seq(createStageInfo(0, 5), createStageInfo(1, 3), createStageInfo(2, 3))
    sc.listenerBus.postToAll(SparkListenerJobStart(0, 0, stages, sc.getLocalProperties))
    sc.listenerBus.postToAll(SparkListenerStageSubmitted(stages(0)))

    // Verify that we're capped at number of tasks in the stage
    assert(numExecutorsTarget(manager) === 0)
    assert(numExecutorsToAdd(manager) === 1)
    assert(addExecutors(manager) === 1)
    assert(numExecutorsTarget(manager) === 1)
    assert(numExecutorsToAdd(manager) === 2)
    assert(addExecutors(manager) === 2)
    assert(numExecutorsTarget(manager) === 3)
    assert(numExecutorsToAdd(manager) === 4)
    assert(addExecutors(manager) === 2)
    assert(numExecutorsTarget(manager) === 5)
    assert(numExecutorsToAdd(manager) === 1)

    // Verify that running a task doesn't affect the target
    sc.listenerBus.postToAll(SparkListenerStageSubmitted(stages(1)))
    sc.listenerBus.postToAll(SparkListenerExecutorAdded(
      0L, "executor-1", new ExecutorInfo("host1", 1, Map.empty)))
    sc.listenerBus.postToAll(SparkListenerTaskStart(1, 0, createTaskInfo(0, 0, "executor-1")))
    assert(numExecutorsTarget(manager) === 5)
    assert(addExecutors(manager) === 1)
    assert(numExecutorsTarget(manager) === 6)
    assert(numExecutorsToAdd(manager) === 2)
    assert(addExecutors(manager) === 2)
    assert(numExecutorsTarget(manager) === 8)
    assert(numExecutorsToAdd(manager) === 4)
    assert(addExecutors(manager) === 0)
    assert(numExecutorsTarget(manager) === 8)
    assert(numExecutorsToAdd(manager) === 1)

    // Verify that re-running a task doesn't blow things up
    sc.listenerBus.postToAll(SparkListenerStageSubmitted(stages(2)))
    sc.listenerBus.postToAll(SparkListenerTaskStart(2, 0, createTaskInfo(0, 0, "executor-1")))
    sc.listenerBus.postToAll(SparkListenerTaskStart(2, 0, createTaskInfo(1, 0, "executor-1")))
    assert(addExecutors(manager) === 1)
    assert(numExecutorsTarget(manager) === 9)
    assert(numExecutorsToAdd(manager) === 2)
    assert(addExecutors(manager) === 1)
    assert(numExecutorsTarget(manager) === 10)
    assert(numExecutorsToAdd(manager) === 1)

    // Verify that running a task once we're at our limit doesn't blow things up
    sc.listenerBus.postToAll(SparkListenerTaskStart(2, 0, createTaskInfo(0, 1, "executor-1")))
    assert(addExecutors(manager) === 0)
    assert(numExecutorsTarget(manager) === 10)
  }

  test("add executors capped by max concurrent tasks for a job group with single core executors") {
    val conf = new SparkConf()
      .setMaster("myDummyLocalExternalClusterManager")
      .setAppName("test-executor-allocation-manager")
      .set("spark.dynamicAllocation.enabled", "true")
      .set("spark.dynamicAllocation.testing", "true")
      .set("spark.job.group1.maxConcurrentTasks", "2")
      .set("spark.job.group2.maxConcurrentTasks", "5")
    val sc = new SparkContext(conf)
    contexts += sc
    sc.setJobGroup("group1", "", false)

    val manager = sc.executorAllocationManager.get
    val stages = Seq(createStageInfo(0, 10), createStageInfo(1, 10))
    // Submit the job and stage start/submit events
    sc.listenerBus.postToAll(SparkListenerJobStart(0, 0, stages, sc.getLocalProperties))
    sc.listenerBus.postToAll(SparkListenerStageSubmitted(stages(0)))

    // Verify that we're capped at number of max concurrent tasks in the stage
    assert(maxNumExecutorsNeeded(manager) === 2)

    // Submit another stage in the same job
    sc.listenerBus.postToAll(SparkListenerStageSubmitted(stages(1)))
    assert(maxNumExecutorsNeeded(manager) === 2)

    sc.listenerBus.postToAll(SparkListenerStageCompleted(stages(0)))
    sc.listenerBus.postToAll(SparkListenerStageCompleted(stages(1)))
    sc.listenerBus.postToAll(SparkListenerJobEnd(0, 10, JobSucceeded))

    // Submit a new job in the same job group
    val stage2 = createStageInfo(2, 20)
    sc.listenerBus.postToAll(SparkListenerJobStart(1, 0, Seq{stage2}, sc.getLocalProperties))
    sc.listenerBus.postToAll(SparkListenerStageSubmitted(stage2))
    assert(maxNumExecutorsNeeded(manager) === 2)

    sc.listenerBus.postToAll(SparkListenerStageCompleted(stage2))
    sc.listenerBus.postToAll(SparkListenerJobEnd(1, 10, JobSucceeded))

    // Set another jobGroup
    sc.setJobGroup("group2", "", false)

    val stage3 = createStageInfo(3, 20)
    sc.listenerBus.postToAll(SparkListenerJobStart(2, 0, Seq{stage3}, sc.getLocalProperties))
    sc.listenerBus.postToAll(SparkListenerStageSubmitted(stage3))
    assert(maxNumExecutorsNeeded(manager) === 5)

    sc.listenerBus.postToAll(SparkListenerStageCompleted(stage3))
    sc.listenerBus.postToAll(SparkListenerJobEnd(2, 10, JobSucceeded))

    // Clear jobGroup
    sc.clearJobGroup()

    val stage4 = createStageInfo(4, 50)
    sc.listenerBus.postToAll(SparkListenerJobStart(2, 0, Seq{stage4}, sc.getLocalProperties))
    sc.listenerBus.postToAll(SparkListenerStageSubmitted(stage4))
    assert(maxNumExecutorsNeeded(manager) === 50)
  }

  test("add executors capped by max concurrent tasks for a job group with multi cores executors") {
    val conf = new SparkConf()
      .setMaster("myDummyLocalExternalClusterManager")
      .setAppName("test-executor-allocation-manager")
      .set("spark.dynamicAllocation.enabled", "true")
      .set("spark.dynamicAllocation.testing", "true")
      .set("spark.job.group1.maxConcurrentTasks", "2")
      .set("spark.job.group2.maxConcurrentTasks", "5")
      .set("spark.executor.cores", "3")
    val sc = new SparkContext(conf)
    contexts += sc
    sc.setJobGroup("group1", "", false)

    val manager = sc.executorAllocationManager.get
    val stages = Seq(createStageInfo(0, 10), createStageInfo(1, 10))
    // Submit the job and stage start/submit events
    sc.listenerBus.postToAll(SparkListenerJobStart(0, 0, stages, sc.getLocalProperties))
    sc.listenerBus.postToAll(SparkListenerStageSubmitted(stages(0)))

    // Verify that we're capped at number of max concurrent tasks in the stage
    assert(maxNumExecutorsNeeded(manager) === 1)

    // Submit another stage in the same job
    sc.listenerBus.postToAll(SparkListenerStageSubmitted(stages(1)))
    assert(maxNumExecutorsNeeded(manager) === 1)

    sc.listenerBus.postToAll(SparkListenerStageCompleted(stages(0)))
    sc.listenerBus.postToAll(SparkListenerStageCompleted(stages(1)))
    sc.listenerBus.postToAll(SparkListenerJobEnd(0, 10, JobSucceeded))

    // Submit a new job in the same job group
    val stage2 = createStageInfo(2, 20)
    sc.listenerBus.postToAll(SparkListenerJobStart(1, 0, Seq(stage2), sc.getLocalProperties))
    sc.listenerBus.postToAll(SparkListenerStageSubmitted(stage2))
    assert(maxNumExecutorsNeeded(manager) === 1)

    sc.listenerBus.postToAll(SparkListenerStageCompleted(stage2))
    sc.listenerBus.postToAll(SparkListenerJobEnd(1, 10, JobSucceeded))

    // Set another jobGroup
    sc.setJobGroup("group2", "", false)

    val stage3 = createStageInfo(3, 20)
    sc.listenerBus.postToAll(SparkListenerJobStart(2, 0, Seq(stage3), sc.getLocalProperties))
    sc.listenerBus.postToAll(SparkListenerStageSubmitted(stage3))
    assert(maxNumExecutorsNeeded(manager) === 2)

    sc.listenerBus.postToAll(SparkListenerStageCompleted(stage3))
    sc.listenerBus.postToAll(SparkListenerJobEnd(2, 10, JobSucceeded))

    // Clear jobGroup
    sc.clearJobGroup()

    val stage4 = createStageInfo(4, 50)
    sc.listenerBus.postToAll(SparkListenerJobStart(2, 0, Seq(stage4), sc.getLocalProperties))
    sc.listenerBus.postToAll(SparkListenerStageSubmitted(stage4))
    assert(maxNumExecutorsNeeded(manager) === 17)
  }

  test("add executors capped by max concurrent tasks for concurrent job groups") {
    val conf = new SparkConf()
      .setMaster("myDummyLocalExternalClusterManager")
      .setAppName("test-executor-allocation-manager")
      .set("spark.dynamicAllocation.enabled", "true")
      .set("spark.dynamicAllocation.testing", "true")
      .set("spark.job.group1.maxConcurrentTasks", "5")
      .set("spark.job.group2.maxConcurrentTasks", "11")
      .set("spark.job.group3.maxConcurrentTasks", "17")
    val sc = new SparkContext(conf)
    contexts += sc

    val manager = sc.executorAllocationManager.get

    // Submit a job in group1
    sc.setJobGroup("group1", "", false)
    val stages = Seq(createStageInfo(0, 2), createStageInfo(1, 10))
    // Submit the job and stage start/submit events
    sc.listenerBus.postToAll(SparkListenerJobStart(0, 0, stages, sc.getLocalProperties))
    sc.listenerBus.postToAll(SparkListenerStageSubmitted(stages(0)))

    // Verify that we're capped at number of max concurrent tasks in the job group
    assert(maxNumExecutorsNeeded(manager) === 2)

    // Submit another stage in the same job
    sc.listenerBus.postToAll(SparkListenerStageSubmitted(stages(1)))
    assert(maxNumExecutorsNeeded(manager) === 5)

    // Submit a job in group 2
    sc.setJobGroup("group2", "", false)
    val stage2 = createStageInfo(2, 20)
    sc.listenerBus.postToAll(SparkListenerJobStart(1, 0, Seq(stage2), sc.getLocalProperties))
    sc.listenerBus.postToAll(SparkListenerStageSubmitted(stage2))
    assert(maxNumExecutorsNeeded(manager) === 16) // 5 + 11

    // Submit a job in group 3
    sc.setJobGroup("group3", "", false)
    val stage3 = createStageInfo(3, 50)
    sc.listenerBus.postToAll(SparkListenerJobStart(2, 0, Seq(stage3), sc.getLocalProperties))
    sc.listenerBus.postToAll(SparkListenerStageSubmitted(stage3))
    assert(maxNumExecutorsNeeded(manager) === 33) // 5 + 11 + 17

    // Mark job in group 2 as complete
    sc.listenerBus.postToAll(SparkListenerStageCompleted(stage2))
    sc.listenerBus.postToAll(SparkListenerJobEnd(1, 20, JobSucceeded))
    assert(maxNumExecutorsNeeded(manager) === 22) // 33 - 11

    // Mark job in group 1 as complete
    sc.listenerBus.postToAll(SparkListenerStageCompleted(stages(0)))
    sc.listenerBus.postToAll(SparkListenerStageCompleted(stages(1)))
    sc.listenerBus.postToAll(SparkListenerJobEnd(0, 10, JobSucceeded))
    assert(maxNumExecutorsNeeded(manager) === 17) // 22 - 5

    // Submit a job without any job group
    sc.clearJobGroup()
    val stage4 = createStageInfo(4, 333)
    sc.listenerBus.postToAll(SparkListenerJobStart(4, 0, Seq(stage4), sc.getLocalProperties))
    sc.listenerBus.postToAll(SparkListenerStageSubmitted(stage4))
    assert(maxNumExecutorsNeeded(manager) === 350) // 17 + 333

    // Mark job without job group as complete
    sc.listenerBus.postToAll(SparkListenerStageCompleted(stage4))
    sc.listenerBus.postToAll(SparkListenerJobEnd(4, 20, JobSucceeded))
    assert(maxNumExecutorsNeeded(manager) === 17) // 350 - 333
  }

  test("cancel pending executors when no longer needed") {
    sc = createSparkContext(0, 10, 0)
    val manager = sc.executorAllocationManager.get
    val stage0 = createStageInfo(2, 5)
    sc.listenerBus.postToAll(SparkListenerJobStart(0, 0, Seq(stage0), sc.getLocalProperties))
    sc.listenerBus.postToAll(SparkListenerStageSubmitted(stage0))

    assert(numExecutorsTarget(manager) === 0)
    assert(numExecutorsToAdd(manager) === 1)
    assert(addExecutors(manager) === 1)
    assert(numExecutorsTarget(manager) === 1)
    assert(numExecutorsToAdd(manager) === 2)
    assert(addExecutors(manager) === 2)
    assert(numExecutorsTarget(manager) === 3)

    val task1Info = createTaskInfo(0, 0, "executor-1")
    sc.listenerBus.postToAll(SparkListenerTaskStart(2, 0, task1Info))

    assert(numExecutorsToAdd(manager) === 4)
    assert(addExecutors(manager) === 2)

    val task2Info = createTaskInfo(1, 0, "executor-1")
    sc.listenerBus.postToAll(SparkListenerTaskStart(2, 0, task2Info))
    sc.listenerBus.postToAll(SparkListenerTaskEnd(2, 0, null, Success, task1Info, null))
    sc.listenerBus.postToAll(SparkListenerTaskEnd(2, 0, null, Success, task2Info, null))

    assert(adjustRequestedExecutors(manager) === -1)
  }

  test("remove executors") {
    sc = createSparkContext(5, 10, 5)
    val manager = sc.executorAllocationManager.get
    (1 to 10).map(_.toString).foreach { id => onExecutorAdded(manager, id) }

    // Keep removing until the limit is reached
    assert(executorsPendingToRemove(manager).isEmpty)
    assert(removeExecutor(manager, "1"))
    assert(executorsPendingToRemove(manager).size === 1)
    assert(executorsPendingToRemove(manager).contains("1"))
    assert(removeExecutor(manager, "2"))
    assert(removeExecutor(manager, "3"))
    assert(executorsPendingToRemove(manager).size === 3)
    assert(executorsPendingToRemove(manager).contains("2"))
    assert(executorsPendingToRemove(manager).contains("3"))
    assert(!removeExecutor(manager, "100")) // remove non-existent executors
    assert(!removeExecutor(manager, "101"))
    assert(executorsPendingToRemove(manager).size === 3)
    assert(removeExecutor(manager, "4"))
    assert(removeExecutor(manager, "5"))
    assert(!removeExecutor(manager, "6")) // reached the limit of 5
    assert(executorsPendingToRemove(manager).size === 5)
    assert(executorsPendingToRemove(manager).contains("4"))
    assert(executorsPendingToRemove(manager).contains("5"))
    assert(!executorsPendingToRemove(manager).contains("6"))

    // Kill executors previously requested to remove
    onExecutorRemoved(manager, "1")
    assert(executorsPendingToRemove(manager).size === 4)
    assert(!executorsPendingToRemove(manager).contains("1"))
    onExecutorRemoved(manager, "2")
    onExecutorRemoved(manager, "3")
    assert(executorsPendingToRemove(manager).size === 2)
    assert(!executorsPendingToRemove(manager).contains("2"))
    assert(!executorsPendingToRemove(manager).contains("3"))
    onExecutorRemoved(manager, "2") // duplicates should not count
    onExecutorRemoved(manager, "3")
    assert(executorsPendingToRemove(manager).size === 2)
    onExecutorRemoved(manager, "4")
    onExecutorRemoved(manager, "5")
    assert(executorsPendingToRemove(manager).isEmpty)

    // Try removing again
    // This should still fail because the number pending + running is still at the limit
    assert(!removeExecutor(manager, "7"))
    assert(executorsPendingToRemove(manager).isEmpty)
    assert(!removeExecutor(manager, "8"))
    assert(executorsPendingToRemove(manager).isEmpty)
  }

  test("remove multiple executors") {
    sc = createSparkContext(5, 10, 5)
    val manager = sc.executorAllocationManager.get
    (1 to 10).map(_.toString).foreach { id => onExecutorAdded(manager, id) }

    // Keep removing until the limit is reached
    assert(executorsPendingToRemove(manager).isEmpty)
    assert(removeExecutors(manager, Seq("1")) === Seq("1"))
    assert(executorsPendingToRemove(manager).size === 1)
    assert(executorsPendingToRemove(manager).contains("1"))
    assert(removeExecutors(manager, Seq("2", "3")) === Seq("2", "3"))
    assert(executorsPendingToRemove(manager).size === 3)
    assert(executorsPendingToRemove(manager).contains("2"))
    assert(executorsPendingToRemove(manager).contains("3"))
    assert(!removeExecutor(manager, "100")) // remove non-existent executors
    assert(removeExecutors(manager, Seq("101", "102")) !== Seq("101", "102"))
    assert(executorsPendingToRemove(manager).size === 3)
    assert(removeExecutor(manager, "4"))
    assert(removeExecutors(manager, Seq("5")) === Seq("5"))
    assert(!removeExecutor(manager, "6")) // reached the limit of 5
    assert(executorsPendingToRemove(manager).size === 5)
    assert(executorsPendingToRemove(manager).contains("4"))
    assert(executorsPendingToRemove(manager).contains("5"))
    assert(!executorsPendingToRemove(manager).contains("6"))

    // Kill executors previously requested to remove
    onExecutorRemoved(manager, "1")
    assert(executorsPendingToRemove(manager).size === 4)
    assert(!executorsPendingToRemove(manager).contains("1"))
    onExecutorRemoved(manager, "2")
    onExecutorRemoved(manager, "3")
    assert(executorsPendingToRemove(manager).size === 2)
    assert(!executorsPendingToRemove(manager).contains("2"))
    assert(!executorsPendingToRemove(manager).contains("3"))
    onExecutorRemoved(manager, "2") // duplicates should not count
    onExecutorRemoved(manager, "3")
    assert(executorsPendingToRemove(manager).size === 2)
    onExecutorRemoved(manager, "4")
    onExecutorRemoved(manager, "5")
    assert(executorsPendingToRemove(manager).isEmpty)

    // Try removing again
    // This should still fail because the number pending + running is still at the limit
    assert(!removeExecutor(manager, "7"))
    assert(executorsPendingToRemove(manager).isEmpty)
    assert(removeExecutors(manager, Seq("8")) !== Seq("8"))
    assert(executorsPendingToRemove(manager).isEmpty)
  }

  test ("interleaving add and remove") {
    sc = createSparkContext(5, 10, 5)
    val manager = sc.executorAllocationManager.get
    val stage0 = createStageInfo(0, 1000)
    sc.listenerBus.postToAll(SparkListenerJobStart(0, 0, Seq(stage0), sc.getLocalProperties))
    sc.listenerBus.postToAll(SparkListenerStageSubmitted(stage0))

    // Add a few executors
    assert(addExecutors(manager) === 1)
    assert(addExecutors(manager) === 2)
    onExecutorAdded(manager, "1")
    onExecutorAdded(manager, "2")
    onExecutorAdded(manager, "3")
    onExecutorAdded(manager, "4")
    onExecutorAdded(manager, "5")
    onExecutorAdded(manager, "6")
    onExecutorAdded(manager, "7")
    onExecutorAdded(manager, "8")
    assert(executorIds(manager).size === 8)

    // Remove until limit
    assert(removeExecutor(manager, "1"))
    assert(removeExecutors(manager, Seq("2", "3")) === Seq("2", "3"))
    assert(!removeExecutor(manager, "4")) // lower limit reached
    assert(!removeExecutor(manager, "5"))
    onExecutorRemoved(manager, "1")
    onExecutorRemoved(manager, "2")
    onExecutorRemoved(manager, "3")
    assert(executorIds(manager).size === 5)

    // Add until limit
    assert(addExecutors(manager) === 2) // upper limit reached
    assert(addExecutors(manager) === 0)
    assert(!removeExecutor(manager, "4")) // still at lower limit
    assert((manager, Seq("5")) !== Seq("5"))
    onExecutorAdded(manager, "9")
    onExecutorAdded(manager, "10")
    onExecutorAdded(manager, "11")
    onExecutorAdded(manager, "12")
    onExecutorAdded(manager, "13")
    assert(executorIds(manager).size === 10)

    // Remove succeeds again, now that we are no longer at the lower limit
    assert(removeExecutors(manager, Seq("4", "5", "6")) === Seq("4", "5", "6"))
    assert(removeExecutor(manager, "7"))
    assert(executorIds(manager).size === 10)
    assert(addExecutors(manager) === 0)
    onExecutorRemoved(manager, "4")
    onExecutorRemoved(manager, "5")
    assert(executorIds(manager).size === 8)

    // Number of executors pending restarts at 1
    assert(numExecutorsToAdd(manager) === 1)
    assert(addExecutors(manager) === 0)
    assert(executorIds(manager).size === 8)
    onExecutorRemoved(manager, "6")
    onExecutorRemoved(manager, "7")
    onExecutorAdded(manager, "14")
    onExecutorAdded(manager, "15")
    assert(executorIds(manager).size === 8)
    assert(addExecutors(manager) === 0) // still at upper limit
    onExecutorAdded(manager, "16")
    onExecutorAdded(manager, "17")
    assert(executorIds(manager).size === 10)
    assert(numExecutorsTarget(manager) === 10)
  }

  test("starting/canceling add timer") {
    sc = createSparkContext(2, 10, 2)
    val clock = new ManualClock(8888L)
    val manager = sc.executorAllocationManager.get
    manager.setClock(clock)

    // Starting add timer is idempotent
    assert(addTime(manager) === NOT_SET)
    onSchedulerBacklogged(manager)
    val firstAddTime = addTime(manager)
    assert(firstAddTime === clock.getTimeMillis + schedulerBacklogTimeout * 1000)
    clock.advance(100L)
    onSchedulerBacklogged(manager)
    assert(addTime(manager) === firstAddTime) // timer is already started
    clock.advance(200L)
    onSchedulerBacklogged(manager)
    assert(addTime(manager) === firstAddTime)
    onSchedulerQueueEmpty(manager)

    // Restart add timer
    clock.advance(1000L)
    assert(addTime(manager) === NOT_SET)
    onSchedulerBacklogged(manager)
    val secondAddTime = addTime(manager)
    assert(secondAddTime === clock.getTimeMillis + schedulerBacklogTimeout * 1000)
    clock.advance(100L)
    onSchedulerBacklogged(manager)
    assert(addTime(manager) === secondAddTime) // timer is already started
    assert(addTime(manager) !== firstAddTime)
    assert(firstAddTime !== secondAddTime)
  }

  test("starting/canceling remove timers") {
    sc = createSparkContext(2, 10, 2)
    val clock = new ManualClock(14444L)
    val manager = sc.executorAllocationManager.get
    manager.setClock(clock)

    executorIds(manager).asInstanceOf[mutable.Set[String]] ++= List("1", "2", "3")

    // Starting remove timer is idempotent for each executor
    assert(removeTimes(manager).isEmpty)
    onExecutorIdle(manager, "1")
    assert(removeTimes(manager).size === 1)
    assert(removeTimes(manager).contains("1"))
    val firstRemoveTime = removeTimes(manager)("1")
    assert(firstRemoveTime === clock.getTimeMillis + executorIdleTimeout * 1000)
    clock.advance(100L)
    onExecutorIdle(manager, "1")
    assert(removeTimes(manager)("1") === firstRemoveTime) // timer is already started
    clock.advance(200L)
    onExecutorIdle(manager, "1")
    assert(removeTimes(manager)("1") === firstRemoveTime)
    clock.advance(300L)
    onExecutorIdle(manager, "2")
    assert(removeTimes(manager)("2") !== firstRemoveTime) // different executor
    assert(removeTimes(manager)("2") === clock.getTimeMillis + executorIdleTimeout * 1000)
    clock.advance(400L)
    onExecutorIdle(manager, "3")
    assert(removeTimes(manager)("3") !== firstRemoveTime)
    assert(removeTimes(manager)("3") === clock.getTimeMillis + executorIdleTimeout * 1000)
    assert(removeTimes(manager).size === 3)
    assert(removeTimes(manager).contains("2"))
    assert(removeTimes(manager).contains("3"))

    // Restart remove timer
    clock.advance(1000L)
    onExecutorBusy(manager, "1")
    assert(removeTimes(manager).size === 2)
    onExecutorIdle(manager, "1")
    assert(removeTimes(manager).size === 3)
    assert(removeTimes(manager).contains("1"))
    val secondRemoveTime = removeTimes(manager)("1")
    assert(secondRemoveTime === clock.getTimeMillis + executorIdleTimeout * 1000)
    assert(removeTimes(manager)("1") === secondRemoveTime) // timer is already started
    assert(removeTimes(manager)("1") !== firstRemoveTime)
    assert(firstRemoveTime !== secondRemoveTime)
  }

  test("mock polling loop with no events") {
    sc = createSparkContext(0, 20, 0)
    val manager = sc.executorAllocationManager.get
    val clock = new ManualClock(2020L)
    manager.setClock(clock)

    // No events - we should not be adding or removing
    assert(numExecutorsTarget(manager) === 0)
    assert(executorsPendingToRemove(manager).isEmpty)
    schedule(manager)
    assert(numExecutorsTarget(manager) === 0)
    assert(executorsPendingToRemove(manager).isEmpty)
    clock.advance(100L)
    schedule(manager)
    assert(numExecutorsTarget(manager) === 0)
    assert(executorsPendingToRemove(manager).isEmpty)
    clock.advance(1000L)
    schedule(manager)
    assert(numExecutorsTarget(manager) === 0)
    assert(executorsPendingToRemove(manager).isEmpty)
    clock.advance(10000L)
    schedule(manager)
    assert(numExecutorsTarget(manager) === 0)
    assert(executorsPendingToRemove(manager).isEmpty)
  }

  test("mock polling loop add behavior") {
    sc = createSparkContext(0, 20, 0)
    val clock = new ManualClock(2020L)
    val manager = sc.executorAllocationManager.get
    manager.setClock(clock)
    val stage0 = createStageInfo(0, 1000)
    sc.listenerBus.postToAll(SparkListenerJobStart(0, 0, Seq(stage0), sc.getLocalProperties))
    sc.listenerBus.postToAll(SparkListenerStageSubmitted(stage0))

    // Scheduler queue backlogged
    onSchedulerBacklogged(manager)
    clock.advance(schedulerBacklogTimeout * 1000 / 2)
    schedule(manager)
    assert(numExecutorsTarget(manager) === 0) // timer not exceeded yet
    clock.advance(schedulerBacklogTimeout * 1000)
    schedule(manager)
    assert(numExecutorsTarget(manager) === 1) // first timer exceeded
    clock.advance(sustainedSchedulerBacklogTimeout * 1000 / 2)
    schedule(manager)
    assert(numExecutorsTarget(manager) === 1) // second timer not exceeded yet
    clock.advance(sustainedSchedulerBacklogTimeout * 1000)
    schedule(manager)
    assert(numExecutorsTarget(manager) === 1 + 2) // second timer exceeded
    clock.advance(sustainedSchedulerBacklogTimeout * 1000)
    schedule(manager)
    assert(numExecutorsTarget(manager) === 1 + 2 + 4) // third timer exceeded

    // Scheduler queue drained
    onSchedulerQueueEmpty(manager)
    clock.advance(sustainedSchedulerBacklogTimeout * 1000)
    schedule(manager)
    assert(numExecutorsTarget(manager) === 7) // timer is canceled
    clock.advance(sustainedSchedulerBacklogTimeout * 1000)
    schedule(manager)
    assert(numExecutorsTarget(manager) === 7)

    // Scheduler queue backlogged again
    onSchedulerBacklogged(manager)
    clock.advance(schedulerBacklogTimeout * 1000)
    schedule(manager)
    assert(numExecutorsTarget(manager) === 7 + 1) // timer restarted
    clock.advance(sustainedSchedulerBacklogTimeout * 1000)
    schedule(manager)
    assert(numExecutorsTarget(manager) === 7 + 1 + 2)
    clock.advance(sustainedSchedulerBacklogTimeout * 1000)
    schedule(manager)
    assert(numExecutorsTarget(manager) === 7 + 1 + 2 + 4)
    clock.advance(sustainedSchedulerBacklogTimeout * 1000)
    schedule(manager)
    assert(numExecutorsTarget(manager) === 20) // limit reached
  }

  test("mock polling loop remove behavior") {
    sc = createSparkContext(1, 20, 1)
    val clock = new ManualClock(2020L)
    val manager = sc.executorAllocationManager.get
    manager.setClock(clock)

    // Remove idle executors on timeout
    onExecutorAdded(manager, "executor-1")
    onExecutorAdded(manager, "executor-2")
    onExecutorAdded(manager, "executor-3")
    assert(removeTimes(manager).size === 3)
    assert(executorsPendingToRemove(manager).isEmpty)
    clock.advance(executorIdleTimeout * 1000 / 2)
    schedule(manager)
    assert(removeTimes(manager).size === 3) // idle threshold not reached yet
    assert(executorsPendingToRemove(manager).isEmpty)
    clock.advance(executorIdleTimeout * 1000)
    schedule(manager)
    assert(removeTimes(manager).isEmpty) // idle threshold exceeded
    assert(executorsPendingToRemove(manager).size === 2) // limit reached (1 executor remaining)

    // Mark a subset as busy - only idle executors should be removed
    onExecutorAdded(manager, "executor-4")
    onExecutorAdded(manager, "executor-5")
    onExecutorAdded(manager, "executor-6")
    onExecutorAdded(manager, "executor-7")
    assert(removeTimes(manager).size === 5)              // 5 active executors
    assert(executorsPendingToRemove(manager).size === 2) // 2 pending to be removed
    onExecutorBusy(manager, "executor-4")
    onExecutorBusy(manager, "executor-5")
    onExecutorBusy(manager, "executor-6") // 3 busy and 2 idle (of the 5 active ones)
    schedule(manager)
    assert(removeTimes(manager).size === 2) // remove only idle executors
    assert(!removeTimes(manager).contains("executor-4"))
    assert(!removeTimes(manager).contains("executor-5"))
    assert(!removeTimes(manager).contains("executor-6"))
    assert(executorsPendingToRemove(manager).size === 2)
    clock.advance(executorIdleTimeout * 1000)
    schedule(manager)
    assert(removeTimes(manager).isEmpty) // idle executors are removed
    assert(executorsPendingToRemove(manager).size === 4)
    assert(!executorsPendingToRemove(manager).contains("executor-4"))
    assert(!executorsPendingToRemove(manager).contains("executor-5"))
    assert(!executorsPendingToRemove(manager).contains("executor-6"))

    // Busy executors are now idle and should be removed
    onExecutorIdle(manager, "executor-4")
    onExecutorIdle(manager, "executor-5")
    onExecutorIdle(manager, "executor-6")
    schedule(manager)
    assert(removeTimes(manager).size === 3) // 0 busy and 3 idle
    assert(removeTimes(manager).contains("executor-4"))
    assert(removeTimes(manager).contains("executor-5"))
    assert(removeTimes(manager).contains("executor-6"))
    assert(executorsPendingToRemove(manager).size === 4)
    clock.advance(executorIdleTimeout * 1000)
    schedule(manager)
    assert(removeTimes(manager).isEmpty)
    assert(executorsPendingToRemove(manager).size === 6) // limit reached (1 executor remaining)
  }

  test("listeners trigger add executors correctly") {
    sc = createSparkContext(2, 10, 2)
    val manager = sc.executorAllocationManager.get
    assert(addTime(manager) === NOT_SET)

    // Starting a stage should start the add timer
    val numTasks = 10
    val stages = Seq(createStageInfo(0, numTasks), createStageInfo(1, numTasks),
      createStageInfo(2, numTasks))
    sc.listenerBus.postToAll(SparkListenerJobStart(0, 0, stages, sc.getLocalProperties))
    sc.listenerBus.postToAll(SparkListenerStageSubmitted(stages(0)))
    assert(addTime(manager) !== NOT_SET)

    // Starting a subset of the tasks should not cancel the add timer
    val taskInfos = (0 to numTasks - 1).map { i => createTaskInfo(i, i, "executor-1") }
    taskInfos.tail.foreach { info => sc.listenerBus.postToAll(SparkListenerTaskStart(0, 0, info)) }
    assert(addTime(manager) !== NOT_SET)

    // Starting all remaining tasks should cancel the add timer
    sc.listenerBus.postToAll(SparkListenerTaskStart(0, 0, taskInfos.head))
    assert(addTime(manager) === NOT_SET)

    // Start two different stages
    // The add timer should be canceled only if all tasks in both stages start running
    sc.listenerBus.postToAll(SparkListenerStageSubmitted(stages(1)))
    sc.listenerBus.postToAll(SparkListenerStageSubmitted(stages(2)))
    assert(addTime(manager) !== NOT_SET)
    taskInfos.foreach { info => sc.listenerBus.postToAll(SparkListenerTaskStart(1, 0, info)) }
    assert(addTime(manager) !== NOT_SET)
    taskInfos.foreach { info => sc.listenerBus.postToAll(SparkListenerTaskStart(2, 0, info)) }
    assert(addTime(manager) === NOT_SET)
  }

  test("listeners trigger remove executors correctly") {
    sc = createSparkContext(2, 10, 2)
    val manager = sc.executorAllocationManager.get
    assert(removeTimes(manager).isEmpty)

    // Added executors should start the remove timers for each executor
    (1 to 5).map("executor-" + _).foreach { id => onExecutorAdded(manager, id) }
    assert(removeTimes(manager).size === 5)

    // Starting a task cancel the remove timer for that executor
    sc.listenerBus.postToAll(SparkListenerTaskStart(0, 0, createTaskInfo(0, 0, "executor-1")))
    sc.listenerBus.postToAll(SparkListenerTaskStart(0, 0, createTaskInfo(1, 1, "executor-1")))
    sc.listenerBus.postToAll(SparkListenerTaskStart(0, 0, createTaskInfo(2, 2, "executor-2")))
    assert(removeTimes(manager).size === 3)
    assert(!removeTimes(manager).contains("executor-1"))
    assert(!removeTimes(manager).contains("executor-2"))

    // Finishing all tasks running on an executor should start the remove timer for that executor
    sc.listenerBus.postToAll(SparkListenerTaskEnd(
      0, 0, "task-type", Success, createTaskInfo(0, 0, "executor-1"), new TaskMetrics))
    sc.listenerBus.postToAll(SparkListenerTaskEnd(
      0, 0, "task-type", Success, createTaskInfo(2, 2, "executor-2"), new TaskMetrics))
    assert(removeTimes(manager).size === 4)
    assert(!removeTimes(manager).contains("executor-1")) // executor-1 has not finished yet
    assert(removeTimes(manager).contains("executor-2"))
    sc.listenerBus.postToAll(SparkListenerTaskEnd(
      0, 0, "task-type", Success, createTaskInfo(1, 1, "executor-1"), new TaskMetrics))
    assert(removeTimes(manager).size === 5)
    assert(removeTimes(manager).contains("executor-1")) // executor-1 has now finished
  }

  test("listeners trigger add and remove executor callbacks correctly") {
    sc = createSparkContext(2, 10, 2)
    val manager = sc.executorAllocationManager.get
    assert(executorIds(manager).isEmpty)
    assert(removeTimes(manager).isEmpty)

    // New executors have registered
    sc.listenerBus.postToAll(SparkListenerExecutorAdded(
      0L, "executor-1", new ExecutorInfo("host1", 1, Map.empty)))
    assert(executorIds(manager).size === 1)
    assert(executorIds(manager).contains("executor-1"))
    assert(removeTimes(manager).size === 1)
    assert(removeTimes(manager).contains("executor-1"))
    sc.listenerBus.postToAll(SparkListenerExecutorAdded(
      0L, "executor-2", new ExecutorInfo("host2", 1, Map.empty)))
    assert(executorIds(manager).size === 2)
    assert(executorIds(manager).contains("executor-2"))
    assert(removeTimes(manager).size === 2)
    assert(removeTimes(manager).contains("executor-2"))

    // Existing executors have disconnected
    sc.listenerBus.postToAll(SparkListenerExecutorRemoved(0L, "executor-1", ""))
    assert(executorIds(manager).size === 1)
    assert(!executorIds(manager).contains("executor-1"))
    assert(removeTimes(manager).size === 1)
    assert(!removeTimes(manager).contains("executor-1"))

    // Unknown executor has disconnected
    sc.listenerBus.postToAll(SparkListenerExecutorRemoved(0L, "executor-3", ""))
    assert(executorIds(manager).size === 1)
    assert(removeTimes(manager).size === 1)
  }

  test("SPARK-4951: call onTaskStart before onBlockManagerAdded") {
    sc = createSparkContext(2, 10, 2)
    val manager = sc.executorAllocationManager.get
    assert(executorIds(manager).isEmpty)
    assert(removeTimes(manager).isEmpty)

    sc.listenerBus.postToAll(SparkListenerTaskStart(0, 0, createTaskInfo(0, 0, "executor-1")))
    sc.listenerBus.postToAll(SparkListenerExecutorAdded(
      0L, "executor-1", new ExecutorInfo("host1", 1, Map.empty)))
    assert(executorIds(manager).size === 1)
    assert(executorIds(manager).contains("executor-1"))
    assert(removeTimes(manager).size === 0)
  }

  test("SPARK-4951: onExecutorAdded should not add a busy executor to removeTimes") {
    sc = createSparkContext(2, 10)
    val manager = sc.executorAllocationManager.get
    assert(executorIds(manager).isEmpty)
    assert(removeTimes(manager).isEmpty)
    sc.listenerBus.postToAll(SparkListenerExecutorAdded(
      0L, "executor-1", new ExecutorInfo("host1", 1, Map.empty)))
    sc.listenerBus.postToAll(SparkListenerTaskStart(0, 0, createTaskInfo(0, 0, "executor-1")))

    assert(executorIds(manager).size === 1)
    assert(executorIds(manager).contains("executor-1"))
    assert(removeTimes(manager).size === 0)

    sc.listenerBus.postToAll(SparkListenerExecutorAdded(
      0L, "executor-2", new ExecutorInfo("host1", 1, Map.empty)))
    assert(executorIds(manager).size === 2)
    assert(executorIds(manager).contains("executor-2"))
    assert(removeTimes(manager).size === 1)
    assert(removeTimes(manager).contains("executor-2"))
    assert(!removeTimes(manager).contains("executor-1"))
  }

  test("avoid ramp up when target < running executors") {
    sc = createSparkContext(0, 100000, 0)
    val manager = sc.executorAllocationManager.get
    val stages = Seq(createStageInfo(0, 1000), createStageInfo(1, 1000))
    sc.listenerBus.postToAll(SparkListenerJobStart(0, 0, stages, sc.getLocalProperties))
    sc.listenerBus.postToAll(SparkListenerStageSubmitted(stages(0)))

    assert(addExecutors(manager) === 1)
    assert(addExecutors(manager) === 2)
    assert(addExecutors(manager) === 4)
    assert(addExecutors(manager) === 8)
    assert(numExecutorsTarget(manager) === 15)
    (0 until 15).foreach { i =>
      onExecutorAdded(manager, s"executor-$i")
    }
    assert(executorIds(manager).size === 15)
    sc.listenerBus.postToAll(SparkListenerStageCompleted(stages(0)))

    adjustRequestedExecutors(manager)
    assert(numExecutorsTarget(manager) === 0)

    sc.listenerBus.postToAll(SparkListenerStageSubmitted(stages(1)))
    addExecutors(manager)
    assert(numExecutorsTarget(manager) === 16)
  }

  test("avoid ramp down initial executors until first job is submitted") {
    sc = createSparkContext(2, 5, 3)
    val manager = sc.executorAllocationManager.get
    val clock = new ManualClock(10000L)
    manager.setClock(clock)

    // Verify the initial number of executors
    assert(numExecutorsTarget(manager) === 3)
    schedule(manager)
    // Verify whether the initial number of executors is kept with no pending tasks
    assert(numExecutorsTarget(manager) === 3)

    val stage0 = createStageInfo(1, 2)
    sc.listenerBus.postToAll(SparkListenerJobStart(0, 0, Seq(stage0), sc.getLocalProperties))
    sc.listenerBus.postToAll(SparkListenerStageSubmitted(stage0))
    clock.advance(100L)

    assert(maxNumExecutorsNeeded(manager) === 2)
    schedule(manager)

    // Verify that current number of executors should be ramp down when first job is submitted
    assert(numExecutorsTarget(manager) === 2)
  }

  test("avoid ramp down initial executors until idle executor is timeout") {
    sc = createSparkContext(2, 5, 3)
    val manager = sc.executorAllocationManager.get
    val clock = new ManualClock(10000L)
    manager.setClock(clock)

    // Verify the initial number of executors
    assert(numExecutorsTarget(manager) === 3)
    schedule(manager)
    // Verify the initial number of executors is kept when no pending tasks
    assert(numExecutorsTarget(manager) === 3)
    (0 until 3).foreach { i =>
      onExecutorAdded(manager, s"executor-$i")
    }

    clock.advance(executorIdleTimeout * 1000)

    assert(maxNumExecutorsNeeded(manager) === 0)
    schedule(manager)
    // Verify executor is timeout but numExecutorsTarget is not recalculated
    assert(numExecutorsTarget(manager) === 3)

    // Schedule again to recalculate the numExecutorsTarget after executor is timeout
    schedule(manager)
    // Verify that current number of executors should be ramp down when executor is timeout
    assert(numExecutorsTarget(manager) === 2)
  }

  test("get pending task number and related locality preference") {
    sc = createSparkContext(2, 5, 3)
    val manager = sc.executorAllocationManager.get

    val localityPreferences1 = Seq(
      Seq(TaskLocation("host1"), TaskLocation("host2"), TaskLocation("host3")),
      Seq(TaskLocation("host1"), TaskLocation("host2"), TaskLocation("host4")),
      Seq(TaskLocation("host2"), TaskLocation("host3"), TaskLocation("host4")),
      Seq.empty,
      Seq.empty
    )
    val localityPreferences2 = Seq(
      Seq(TaskLocation("host2"), TaskLocation("host3"), TaskLocation("host5")),
      Seq(TaskLocation("host3"), TaskLocation("host4"), TaskLocation("host5")),
      Seq.empty
    )
    val stageInfos = Seq(createStageInfo(1, 5, localityPreferences1),
      createStageInfo(2, 3, localityPreferences2))

    sc.listenerBus.postToAll(SparkListenerJobStart(0, 0, stageInfos, sc.getLocalProperties))
    sc.listenerBus.postToAll(SparkListenerStageSubmitted(stageInfos(0)))

    assert(localityAwareTasks(manager) === 3)
    assert(hostToLocalTaskCount(manager) ===
      Map("host1" -> 2, "host2" -> 3, "host3" -> 2, "host4" -> 2))


    sc.listenerBus.postToAll(SparkListenerStageSubmitted(stageInfos(1)))

    assert(localityAwareTasks(manager) === 5)
    assert(hostToLocalTaskCount(manager) ===
      Map("host1" -> 2, "host2" -> 4, "host3" -> 4, "host4" -> 3, "host5" -> 2))

    sc.listenerBus.postToAll(SparkListenerStageCompleted(stageInfos(0)))
    assert(localityAwareTasks(manager) === 2)
    assert(hostToLocalTaskCount(manager) ===
      Map("host2" -> 1, "host3" -> 2, "host4" -> 1, "host5" -> 2))
  }

  test("SPARK-8366: maxNumExecutorsNeeded should properly handle failed tasks") {
    sc = createSparkContext()
    val manager = sc.executorAllocationManager.get
    assert(maxNumExecutorsNeeded(manager) === 0)

    val stage0 = createStageInfo(0, 1)
    sc.listenerBus.postToAll(SparkListenerJobStart(0, 0, Seq(stage0), sc.getLocalProperties))
    sc.listenerBus.postToAll(SparkListenerStageSubmitted(stage0))
    assert(maxNumExecutorsNeeded(manager) === 1)

    val taskInfo = createTaskInfo(1, 1, "executor-1")
    sc.listenerBus.postToAll(SparkListenerTaskStart(0, 0, taskInfo))
    assert(maxNumExecutorsNeeded(manager) === 1)

    // If the task is failed, we expect it to be resubmitted later.
    val taskEndReason = ExceptionFailure(null, null, null, null, None)
    sc.listenerBus.postToAll(SparkListenerTaskEnd(0, 0, null, taskEndReason, taskInfo, null))
    assert(maxNumExecutorsNeeded(manager) === 1)
  }

  test("reset the state of allocation manager") {
    sc = createSparkContext()
    val manager = sc.executorAllocationManager.get
    assert(numExecutorsTarget(manager) === 1)
    assert(numExecutorsToAdd(manager) === 1)

    // Allocation manager is reset when adding executor requests are sent without reporting back
    // executor added.
    val stage0 = createStageInfo(0, 10)
    sc.listenerBus.postToAll(SparkListenerJobStart(0, 0, Seq(stage0), sc.getLocalProperties))
    sc.listenerBus.postToAll(SparkListenerStageSubmitted(stage0))

    assert(addExecutors(manager) === 1)
    assert(numExecutorsTarget(manager) === 2)
    assert(addExecutors(manager) === 2)
    assert(numExecutorsTarget(manager) === 4)
    assert(addExecutors(manager) === 1)
    assert(numExecutorsTarget(manager) === 5)

    manager.reset()
    assert(numExecutorsTarget(manager) === 1)
    assert(numExecutorsToAdd(manager) === 1)
    assert(executorIds(manager) === Set.empty)

    // Allocation manager is reset when executors are added.
    sc.listenerBus.postToAll(SparkListenerStageSubmitted(createStageInfo(0, 10)))

    addExecutors(manager)
    addExecutors(manager)
    addExecutors(manager)
    assert(numExecutorsTarget(manager) === 5)

    onExecutorAdded(manager, "first")
    onExecutorAdded(manager, "second")
    onExecutorAdded(manager, "third")
    onExecutorAdded(manager, "fourth")
    onExecutorAdded(manager, "fifth")
    assert(executorIds(manager) === Set("first", "second", "third", "fourth", "fifth"))

    // Cluster manager lost will make all the live executors lost, so here simulate this behavior
    onExecutorRemoved(manager, "first")
    onExecutorRemoved(manager, "second")
    onExecutorRemoved(manager, "third")
    onExecutorRemoved(manager, "fourth")
    onExecutorRemoved(manager, "fifth")

    manager.reset()
    assert(numExecutorsTarget(manager) === 1)
    assert(numExecutorsToAdd(manager) === 1)
    assert(executorIds(manager) === Set.empty)
    assert(removeTimes(manager) === Map.empty)

    // Allocation manager is reset when executors are pending to remove
    addExecutors(manager)
    addExecutors(manager)
    addExecutors(manager)
    assert(numExecutorsTarget(manager) === 5)

    onExecutorAdded(manager, "first")
    onExecutorAdded(manager, "second")
    onExecutorAdded(manager, "third")
    onExecutorAdded(manager, "fourth")
    onExecutorAdded(manager, "fifth")
    assert(executorIds(manager) === Set("first", "second", "third", "fourth", "fifth"))

    removeExecutor(manager, "first")
    removeExecutors(manager, Seq("second", "third"))
    assert(executorsPendingToRemove(manager) === Set("first", "second", "third"))
    assert(executorIds(manager) === Set("first", "second", "third", "fourth", "fifth"))


    // Cluster manager lost will make all the live executors lost, so here simulate this behavior
    onExecutorRemoved(manager, "first")
    onExecutorRemoved(manager, "second")
    onExecutorRemoved(manager, "third")
    onExecutorRemoved(manager, "fourth")
    onExecutorRemoved(manager, "fifth")

    manager.reset()

    assert(numExecutorsTarget(manager) === 1)
    assert(numExecutorsToAdd(manager) === 1)
    assert(executorsPendingToRemove(manager) === Set.empty)
    assert(removeTimes(manager) === Map.empty)
  }

  private def createSparkContext(
      minExecutors: Int = 1,
      maxExecutors: Int = 5,
      initialExecutors: Int = 1): SparkContext = {
    val conf = new SparkConf()
      .setMaster("myDummyLocalExternalClusterManager")
      .setAppName("test-executor-allocation-manager")
      .set("spark.dynamicAllocation.enabled", "true")
      .set("spark.dynamicAllocation.minExecutors", minExecutors.toString)
      .set("spark.dynamicAllocation.maxExecutors", maxExecutors.toString)
      .set("spark.dynamicAllocation.initialExecutors", initialExecutors.toString)
      .set("spark.dynamicAllocation.schedulerBacklogTimeout",
          s"${schedulerBacklogTimeout.toString}s")
      .set("spark.dynamicAllocation.sustainedSchedulerBacklogTimeout",
        s"${sustainedSchedulerBacklogTimeout.toString}s")
      .set("spark.dynamicAllocation.executorIdleTimeout", s"${executorIdleTimeout.toString}s")
      .set("spark.dynamicAllocation.testing", "true")
    val sc = new SparkContext(conf)
    contexts += sc
    sc
  }

}

/**
 * Helper methods for testing ExecutorAllocationManager.
 * This includes methods to access private methods and fields in ExecutorAllocationManager.
 */
private object ExecutorAllocationManagerSuite extends PrivateMethodTester {
  private val schedulerBacklogTimeout = 1L
  private val sustainedSchedulerBacklogTimeout = 2L
  private val executorIdleTimeout = 3L

  private def createStageInfo(
      stageId: Int,
      numTasks: Int,
      taskLocalityPreferences: Seq[Seq[TaskLocation]] = Seq.empty
    ): StageInfo = {
    new StageInfo(stageId, 0, "name", numTasks, Seq.empty, Seq.empty, "no details",
      taskLocalityPreferences = taskLocalityPreferences)
  }

  private def createTaskInfo(taskId: Int, taskIndex: Int, executorId: String): TaskInfo = {
    new TaskInfo(taskId, taskIndex, 0, 0, executorId, "", TaskLocality.ANY, speculative = false)
  }

  /* ------------------------------------------------------- *
   | Helper methods for accessing private methods and fields |
   * ------------------------------------------------------- */

  private val _numExecutorsToAdd = PrivateMethod[Int]('numExecutorsToAdd)
  private val _numExecutorsTarget = PrivateMethod[Int]('numExecutorsTarget)
  private val _maxNumExecutorsNeeded = PrivateMethod[Int]('maxNumExecutorsNeeded)
  private val _executorsPendingToRemove =
    PrivateMethod[collection.Set[String]]('executorsPendingToRemove)
  private val _executorIds = PrivateMethod[collection.Set[String]]('executorIds)
  private val _addTime = PrivateMethod[Long]('addTime)
  private val _removeTimes = PrivateMethod[collection.Map[String, Long]]('removeTimes)
  private val _schedule = PrivateMethod[Unit]('schedule)
  private val _addExecutors = PrivateMethod[Int]('addExecutors)
  private val _updateAndSyncNumExecutorsTarget =
    PrivateMethod[Int]('updateAndSyncNumExecutorsTarget)
  private val _removeExecutor = PrivateMethod[Boolean]('removeExecutor)
  private val _removeExecutors = PrivateMethod[Seq[String]]('removeExecutors)
  private val _onExecutorAdded = PrivateMethod[Unit]('onExecutorAdded)
  private val _onExecutorRemoved = PrivateMethod[Unit]('onExecutorRemoved)
  private val _onSchedulerBacklogged = PrivateMethod[Unit]('onSchedulerBacklogged)
  private val _onSchedulerQueueEmpty = PrivateMethod[Unit]('onSchedulerQueueEmpty)
  private val _onExecutorIdle = PrivateMethod[Unit]('onExecutorIdle)
  private val _onExecutorBusy = PrivateMethod[Unit]('onExecutorBusy)
  private val _localityAwareTasks = PrivateMethod[Int]('localityAwareTasks)
  private val _hostToLocalTaskCount = PrivateMethod[Map[String, Int]]('hostToLocalTaskCount)

  private def numExecutorsToAdd(manager: ExecutorAllocationManager): Int = {
    manager invokePrivate _numExecutorsToAdd()
  }

  private def numExecutorsTarget(manager: ExecutorAllocationManager): Int = {
    manager invokePrivate _numExecutorsTarget()
  }

  private def executorsPendingToRemove(
      manager: ExecutorAllocationManager): collection.Set[String] = {
    manager invokePrivate _executorsPendingToRemove()
  }

  private def executorIds(manager: ExecutorAllocationManager): collection.Set[String] = {
    manager invokePrivate _executorIds()
  }

  private def addTime(manager: ExecutorAllocationManager): Long = {
    manager invokePrivate _addTime()
  }

  private def removeTimes(manager: ExecutorAllocationManager): collection.Map[String, Long] = {
    manager invokePrivate _removeTimes()
  }

  private def schedule(manager: ExecutorAllocationManager): Unit = {
    manager invokePrivate _schedule()
  }

  private def maxNumExecutorsNeeded(manager: ExecutorAllocationManager): Int = {
    manager invokePrivate _maxNumExecutorsNeeded()
  }

  private def addExecutors(manager: ExecutorAllocationManager): Int = {
    val maxNumExecutorsNeeded = manager invokePrivate _maxNumExecutorsNeeded()
    manager invokePrivate _addExecutors(maxNumExecutorsNeeded)
  }

  private def adjustRequestedExecutors(manager: ExecutorAllocationManager): Int = {
    manager invokePrivate _updateAndSyncNumExecutorsTarget(0L)
  }

  private def removeExecutor(manager: ExecutorAllocationManager, id: String): Boolean = {
    manager invokePrivate _removeExecutor(id)
  }

  private def removeExecutors(manager: ExecutorAllocationManager, ids: Seq[String]): Seq[String] = {
    manager invokePrivate _removeExecutors(ids)
  }

  private def onExecutorAdded(manager: ExecutorAllocationManager, id: String): Unit = {
    manager invokePrivate _onExecutorAdded(id)
  }

  private def onExecutorRemoved(manager: ExecutorAllocationManager, id: String): Unit = {
    manager invokePrivate _onExecutorRemoved(id)
  }

  private def onSchedulerBacklogged(manager: ExecutorAllocationManager): Unit = {
    manager invokePrivate _onSchedulerBacklogged()
  }

  private def onSchedulerQueueEmpty(manager: ExecutorAllocationManager): Unit = {
    manager invokePrivate _onSchedulerQueueEmpty()
  }

  private def onExecutorIdle(manager: ExecutorAllocationManager, id: String): Unit = {
    manager invokePrivate _onExecutorIdle(id)
  }

  private def onExecutorBusy(manager: ExecutorAllocationManager, id: String): Unit = {
    manager invokePrivate _onExecutorBusy(id)
  }

  private def localityAwareTasks(manager: ExecutorAllocationManager): Int = {
    manager invokePrivate _localityAwareTasks()
  }

  private def hostToLocalTaskCount(manager: ExecutorAllocationManager): Map[String, Int] = {
    manager invokePrivate _hostToLocalTaskCount()
  }
}

/**
 * A cluster manager which wraps around the scheduler and backend for local mode. It is used for
 * testing the dynamic allocation policy.
 */
private class DummyLocalExternalClusterManager extends ExternalClusterManager {

  def canCreate(masterURL: String): Boolean = masterURL == "myDummyLocalExternalClusterManager"

  override def createTaskScheduler(
      sc: SparkContext,
      masterURL: String): TaskScheduler = new TaskSchedulerImpl(sc, 1, isLocal = true)

  override def createSchedulerBackend(
      sc: SparkContext,
      masterURL: String,
      scheduler: TaskScheduler): SchedulerBackend = {
    val sb = new LocalSchedulerBackend(sc.getConf, scheduler.asInstanceOf[TaskSchedulerImpl], 1)
    new DummyLocalSchedulerBackend(sc, sb)
  }

  override def initialize(scheduler: TaskScheduler, backend: SchedulerBackend): Unit = {
    val sc = scheduler.asInstanceOf[TaskSchedulerImpl]
    sc.initialize(backend)
  }
}

/**
 * A scheduler backend which wraps around local scheduler backend and exposes the executor
 * allocation client interface for testing dynamic allocation.
 */
private class DummyLocalSchedulerBackend (sc: SparkContext, sb: SchedulerBackend)
  extends SchedulerBackend with ExecutorAllocationClient {

  override private[spark] def getExecutorIds(): Seq[String] = sc.getExecutorIds()

  override private[spark] def requestTotalExecutors(
      numExecutors: Int,
      localityAwareTasks: Int,
      hostToLocalTaskCount: Map[String, Int]): Boolean =
    sc.requestTotalExecutors(numExecutors, localityAwareTasks, hostToLocalTaskCount)

  override def requestExecutors(numAdditionalExecutors: Int): Boolean =
    sc.requestExecutors(numAdditionalExecutors)

  override def killExecutors(
      executorIds: Seq[String],
      replace: Boolean,
      force: Boolean): Seq[String] = {
    val response = sc.killExecutors(executorIds)
    if (response) {
      executorIds
    } else {
      Seq.empty[String]
    }
  }

  override def start(): Unit = sb.start()

  override def stop(): Unit = sb.stop()

  override def reviveOffers(): Unit = sb.reviveOffers()

  override def defaultParallelism(): Int = sb.defaultParallelism()

  override def killExecutorsOnHost(host: String): Boolean = {
    false
  }
}
