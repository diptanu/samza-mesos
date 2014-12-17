/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package eu.inn.samza.mesos

import eu.inn.samza.mesos.MesosConfig.Config2Mesos
import org.apache.mesos.Protos.{OfferID, Offer, TaskInfo}
import org.apache.samza.config.Config
import org.apache.samza.container.{TaskName, TaskNamesToSystemStreamPartitions}
import org.apache.samza.job.ApplicationStatus
import org.apache.samza.job.ApplicationStatus._
import org.apache.samza.util.{Logging, Util}

import scala.collection.mutable
import scala.collection.JavaConversions._

class SamzaSchedulerState(config: Config) extends Logging {
  var currentStatus: ApplicationStatus = New
  var isHealthy = false

  val initialTaskCount: Int = config.getTaskCount.getOrElse({
    info("No %s specified. Defaulting to one container." format MesosConfig.EXECUTOR_TASK_COUNT)
    1
  })

  val initialSamzaTaskIDs = (0 until initialTaskCount).toSet

  val samzaTaskIDToSSPTaskNames: Map[Int, TaskNamesToSystemStreamPartitions] =
    Util.assignContainerToSSPTaskNames(config, initialTaskCount)

  val taskNameToChangeLogPartitionMapping: Map[TaskName, Int] =
    Util.getTaskNameToChangeLogPartitionMapping(config, samzaTaskIDToSSPTaskNames)

  val tasks: Map[String, MesosTask] = initialSamzaTaskIDs.map(id => {
    val task = new MesosTask(config, this, id)
    (task.getMesosTaskId, task)
  }).toMap

  val preparedTasks: mutable.Map[String, TaskInfo] = mutable.Map()

  val unclaimedTasks: mutable.Set[String] = mutable.Set(tasks.keys.toSeq: _*)
  val pendingTasks: mutable.Set[String] = mutable.Set()
  val runningTasks: mutable.Set[String] = mutable.Set()

  val offerPool: mutable.Map[OfferID, Offer] = mutable.Map()

  def filterTasks(ids: Seq[String]): Set[MesosTask] =
    tasks.filterKeys(ids.contains).map(_._2).toSet

  def dump() = {
    info("Tasks state: unclaimed: %d, pending: %d, running: %d"
      format(unclaimedTasks.size, pendingTasks.size, runningTasks.size))
  }
}