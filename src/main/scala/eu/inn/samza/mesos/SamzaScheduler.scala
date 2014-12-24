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

import java.util.{List => JList, Set => JSet}

import eu.inn.samza.mesos.mapping.TaskOfferMapper
import org.apache.mesos.Protos._
import org.apache.mesos.{Scheduler, SchedulerDriver}
import org.apache.samza.config.Config
import org.apache.samza.util.Logging

import scala.collection.JavaConversions._

class SamzaScheduler(config: Config, state: SamzaSchedulerState, offerMapper: TaskOfferMapper) extends Scheduler with Logging {

  info("Samza scheduler created.")

  override def registered(driver: SchedulerDriver, framework: FrameworkID, master: MasterInfo) {
    info("Samza framework registered")
  }

  override def reregistered(driver: SchedulerDriver, master: MasterInfo): Unit = {
    info("Samza framework re-registered")
  }

  def launch(driver: SchedulerDriver, offer: Offer, tasks: JSet[MesosTask]): Unit = {
    info(s"Assigning ${tasks.size()} Mesos tasks ${tasks.map(_.getMesosTaskId)} to offer ${offer.getId.getValue}.")
    val preparedTasks = tasks.map(_.getBuiltMesosTaskInfo(offer.getSlaveId))
    val status = driver.launchTasks(Seq(offer.getId), preparedTasks)

    debug(s"Result of launching tasks ${tasks.map(_.getMesosTaskId)} is ${status}")

    if (status == Status.DRIVER_RUNNING) {
      state.pendingTasks ++= preparedTasks.map(_.getTaskId.getValue) //TODO task state transitions should probably be encapsulated in SamzaSchedulerState methods
      state.unclaimedTasks --= preparedTasks.map(_.getTaskId.getValue)
    }
    // todo: else what?
  }

  override def resourceOffers(driver: SchedulerDriver, offers: JList[Offer]) {
    debug(s"resourceOffers called with offers ${offers.map(_.getId.getValue)}")

    if (state.unclaimedTasks.nonEmpty) {
      info(s"resourceOffers is trying to allocate resources for Mesos tasks ${state.unclaimedTasks}")
      offerMapper.mapResources(offers, state.filterTasks(state.unclaimedTasks)).foreach { case (offer, tasks) => 
        if (tasks.isEmpty) {
          debug(s"Resource constraints have not been satisfied by offer ${offer.getId.getValue}. Declining.")
          driver.declineOffer(offer.getId)
        } else {
          info(s"Resource constraints for Mesos tasks ${tasks.map(_.getMesosTaskId)} have been satisfied by offer ${offer.getId.getValue}. Launching.")
          launch(driver, offer, tasks)
        }
      }
    }
    else {
      offers.foreach(o => driver.declineOffer(o.getId))
    }
  }

  override def offerRescinded(driver: SchedulerDriver, offer: OfferID): Unit = {
    info(s"offerRescinded called with offer ${offer.getValue}")
  }

  override def statusUpdate(driver: SchedulerDriver, status: TaskStatus) {
    val taskId = status.getTaskId.getValue

    info(s"Mesos task ${taskId} is in state ${status.getState}")

    status.getState match {
      case TaskState.TASK_RUNNING =>
        state.pendingTasks -= taskId
        state.runningTasks += taskId
      case TaskState.TASK_FAILED |
           TaskState.TASK_FINISHED |
           TaskState.TASK_KILLED |
           TaskState.TASK_LOST =>
        state.unclaimedTasks += taskId //TODO task state transitions should probably be encapsulated in SamzaSchedulerState methods
        state.pendingTasks -= taskId
        state.runningTasks -= taskId
        info(s"Mesos task ${taskId} is now unclaimed and needs to be re-scheduled")
      case _ =>
    }

    state.dump()
  }

  override def frameworkMessage(driver: SchedulerDriver, executor: ExecutorID, slave: SlaveID, data: Array[Byte]): Unit = {}

  override def disconnected(driver: SchedulerDriver): Unit = {
    info("Framework has been disconnected")
  }

  override def slaveLost(driver: SchedulerDriver, slave: SlaveID): Unit = {
    info(s"A slave ${slave.getValue} has been lost")
  }

  override def executorLost(driver: SchedulerDriver, executor: ExecutorID, slave: SlaveID, status: Int): Unit = {
    info(s"An executor ${executor.getValue} on slave ${slave.getValue} has been lost.")
  }

  override def error(driver: SchedulerDriver, error: String) {
    info(s"Error reported: ${error}")
  }
}
