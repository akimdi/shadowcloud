package com.karasiq.shadowcloud.actors.events

import com.karasiq.shadowcloud.actors.internal.StringEventBus
import com.karasiq.shadowcloud.actors.messages.RegionEnvelope
import com.karasiq.shadowcloud.index.Chunk
import com.karasiq.shadowcloud.index.diffs.IndexDiff
import com.karasiq.shadowcloud.storage.utils.IndexMerger.RegionKey

import scala.language.postfixOps

object RegionEvents {
  // Events
  sealed trait Event
  case class IndexUpdated(sequenceNr: RegionKey, diff: IndexDiff) extends Event
  case class ChunkWritten(storageId: String, chunk: Chunk) extends Event

  val stream = new StringEventBus[RegionEnvelope](_.regionId)
}