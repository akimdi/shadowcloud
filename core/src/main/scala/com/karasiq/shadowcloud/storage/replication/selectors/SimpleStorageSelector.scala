package com.karasiq.shadowcloud.storage.replication.selectors

import scala.util.Random

import com.karasiq.shadowcloud.actors.context.RegionContext
import com.karasiq.shadowcloud.config.utils.ConfigImplicits
import com.karasiq.shadowcloud.index.diffs.IndexDiff
import com.karasiq.shadowcloud.storage.replication.{ChunkWriteAffinity, StorageSelector}
import com.karasiq.shadowcloud.storage.replication.ChunkStatusProvider.{ChunkStatus, WriteStatus}
import com.karasiq.shadowcloud.storage.replication.RegionStorageProvider.RegionStorage
import com.karasiq.shadowcloud.utils.{SizeUnit, Utils}

class SimpleStorageSelector(region: RegionContext) extends StorageSelector {
  protected object settings extends ConfigImplicits {
    val selectorConfig = region.config.rootConfig.getConfigIfExists("simple-selector")
    val indexRF = region.config.indexReplicationFactor
    val dataRF = region.config.dataReplicationFactor
    val randomize = selectorConfig.withDefault(true, _.getBoolean("randomize"))
    val indexWriteMinSize = selectorConfig.withDefault[Long](SizeUnit.MB * 10, _.getBytes("index-write-min-size"))
  }

  def available(toWrite: Long = 0): Seq[RegionStorage] = {
    region.storages.storages.filter(s ⇒ s.health.online && s.health.canWrite > toWrite).toVector
  }

  def forIndexWrite(diff: IndexDiff): Seq[RegionStorage] = {
    val writable = available(settings.indexWriteMinSize)
    Utils.takeOrAll(writable, settings.indexRF)
  }

  def forRead(status: ChunkStatus): Option[RegionStorage] = {
    val readable = available().filter(storage ⇒ status.availability.isWritten(storage.id) && !status.availability.isFailed(storage.id))
    tryRandomize(readable).headOption
  }

  def forWrite(chunk: ChunkStatus): ChunkWriteAffinity = {
    def generateList(): Seq[String] = {
      def canWriteChunk(storage: RegionStorage): Boolean = {
        !chunk.availability.isWriting(storage.id) && !chunk.waitingChunk.contains(storage.dispatcher)
      }

      val writeSize = chunk.chunk.checksum.encSize
      available(writeSize)
        .filter(canWriteChunk)
        .map(_.id)
    }

    val generatedList = generateList()
    chunk.writeStatus match {
      case WriteStatus.Pending(affinity) ⇒
        val newList = affinity.mandatory.filterNot(chunk.availability.isFailed) ++ generatedList
        affinity.copy(mandatory = selectStoragesToWrite(newList))

      case _ ⇒
        ChunkWriteAffinity(selectStoragesToWrite(generatedList))
    }
  }

  protected def tryRandomize[T](storages: Seq[T]): Seq[T] = {
    if (settings.randomize) Random.shuffle(storages) else storages
  }

  protected def selectStoragesToWrite(storages: Seq[String]): Seq[String] = {
    Utils.takeOrAll(tryRandomize(storages.distinct), settings.dataRF)
  }
}
