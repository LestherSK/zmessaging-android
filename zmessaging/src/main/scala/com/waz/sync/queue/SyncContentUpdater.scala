/*
 * Wire
 * Copyright (C) 2016 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.sync.queue

import com.waz.ZLog._
import com.waz.api.SyncState
import com.waz.content._
import com.waz.model.SyncId
import com.waz.model.sync.SyncJob.SyncJobDao
import com.waz.model.sync._
import com.waz.sync.queue.SyncContentUpdater.{Add, Cmd, Del, Update}
import com.waz.sync.queue.SyncJobMerger.{Merged, Unchanged, Updated}
import com.waz.threading.SerialDispatchQueue
import com.waz.utils._
import com.waz.utils.events.{AggregatingSignal, EventContext, EventStream, Signal}

import scala.collection.mutable
import scala.concurrent.Future

/**
 * Keeps actual SyncJobs in memory, and persists all changes to db.
 * Handles merging of new requests, only adds new jobs if actually needed.
 */
class SyncContentUpdater(storage: ZmsDatabase) {
  import EventContext.Implicits.global

  private implicit val logTag: LogTag = logTagFor[SyncContentUpdater]
  private implicit val dispatcher = new SerialDispatchQueue(name = "SyncContentUpdaterQueue")

  private val mergers = new mutable.HashMap[Any, SyncJobMerger]

  val syncStorageFuture = storage(SyncJobDao.list(_)).future map { jobs =>
    // make sure no job is loaded with Syncing state, this could happen if app is killed while syncing
    jobs map { job =>
      if (job.state == SyncState.SYNCING) {
        verbose(s"found job in state: SYNCING on initial load: $job")
        job.copy(state = SyncState.WAITING)
      } else job
    }
  } map { jobs =>
    returningF(new SyncStorage(storage, jobs)) { storage =>

      jobs foreach { updateMerger(_, storage) }

      storage.onAdded { job =>
        job.request match {
          case SerialConvRequest(conv) =>
            storage.getJobs.filter { j => SerialConvRequest.unapply(j.request).contains(conv) && j.priority > job.priority } foreach { j =>
              storage.update(j.id)(j => j.copy(priority = math.min(j.priority, job.priority), optional = j.optional && job.optional))
            }
          case _ =>
        }
        updateDeps(job, storage)
        updateMerger(job, storage)
      }

      storage.onUpdated { case (prev, updated) => updateMerger(updated, storage) }

      storage.onRemoved { job =>
        mergers.get(job.mergeKey) foreach { merger =>
          merger.remove(job.id)
          if (merger.isEmpty) mergers.remove(job.mergeKey)
        }
      }
    }
  }

  lazy val syncJobs: Signal[Map[SyncId, SyncJob]] = {
    val onChange = EventStream[Cmd]()
    syncStorageFuture.map { syncStorage =>
      syncStorage.onUpdated { case (prev, updated) => onChange ! Update(updated) }
      syncStorage.onAdded   { job => onChange ! Add(job) }
      syncStorage.onRemoved { job => onChange ! Del(job) }
    }

    new AggregatingSignal[Cmd, Map[SyncId, SyncJob]](onChange, listSyncJobs.map(_.map(j => j.id -> j).toMap), { (jobs, cmd) =>
      cmd match {
        case Add(job) => jobs + (job.id -> job)
        case Del(job) => jobs - job.id
        case Update(job) => jobs + (job.id -> job)
      }
    })
  }

  // XXX: this exposes internal SyncStorage instance which should never be used outside of our dispatch queue (as it is not thread safe)
  // We should use some kind of delegate here, which gets closed once body completes
  def syncStorage[A](body: SyncStorage => A): Future[A] = syncStorageFuture map body

  /**
   * Adds new request, merges it to existing request or skips it if duplicate.
   * @return affected (new or updated) SyncJob
   */
  def addSyncJob(job: SyncJob, forceRetry: Boolean = false): Future[SyncJob] = syncStorageFuture map { syncStorage =>

    def onAdded(added: SyncJob) = {
      assert(added.id == job.id)
      verbose(s"addRequest: $job, added: $added")
      added
    }

    def onMerged(merged: SyncJob) = {
      verbose(s"addRequest: $job, merged: $merged")
      if (forceRetry) merged.copy(attempts = 0, startTime = math.min(merged.startTime, job.startTime))
      else merged
    }

    val toSave = merge(job, syncStorage) match {
      case Unchanged => error("Unexpected result from SyncJobMerger"); job
      case Updated(added) => onAdded(added)
      case Merged(merged) => onMerged(merged)
    }
    syncStorage.add(toSave)
  }

  def removeSyncJob(id: SyncId) = syncStorageFuture.map(_.remove(id))

  def getSyncJob(id: SyncId) = syncStorageFuture.map(_.get(id))

  def listSyncJobs = syncStorageFuture.map(_.getJobs)

  def updateSyncJob(id: SyncId)(updater: SyncJob => SyncJob) = syncStorageFuture.map(_.update(id)(updater))

  private def updateMerger(job: SyncJob, storage: SyncStorage) =
    mergers.getOrElseUpdate(job.mergeKey, new SyncJobMerger(job.mergeKey, storage)).insert(job)

  private def merge(job: SyncJob, storage: SyncStorage) =
    mergers.getOrElseUpdate(job.mergeKey, new SyncJobMerger(job.mergeKey, storage)).merge(job)

  private def updateDeps(job: SyncJob, syncStorage: SyncStorage): Unit =
    job.dependsOn foreach { dep => updateSchedule(dep, job.priority, job.optional, syncStorage) }

  private def updateSchedule(id: SyncId, priority: Int, optional: Boolean = false, syncStorage: SyncStorage): Unit =
    syncStorage.update(id) { job =>
      job.copy(priority = math.min(job.priority, priority), optional = job.optional || optional)
    } foreach { job =>
      job.dependsOn foreach { updateSchedule(_, priority, optional, syncStorage) }
    }
}

object SyncContentUpdater {

  sealed trait Cmd {
    val job: SyncJob
  }
  case class Add(job: SyncJob) extends Cmd
  case class Del(job: SyncJob) extends Cmd
  case class Update(job: SyncJob) extends Cmd

}
