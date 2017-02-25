package com.karasiq.shadowcloud.actors.internal

import akka.Done
import akka.actor.{ActorContext, ActorRef}
import akka.event.LoggingAdapter
import akka.stream.IOResult
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.karasiq.shadowcloud.actors.ChunkIODispatcher.{ReadChunk, WriteChunk}
import com.karasiq.shadowcloud.config.StorageConfig
import com.karasiq.shadowcloud.crypto.EncryptionModule
import com.karasiq.shadowcloud.index.Chunk
import com.karasiq.shadowcloud.index.diffs.ChunkIndexDiff
import com.karasiq.shadowcloud.utils.Utils

import scala.collection.mutable
import scala.concurrent.Future
import scala.language.{implicitConversions, postfixOps}
import scala.util.Success

private[actors] object ChunksTracker {
  object Status extends Enumeration {
    val PENDING, STORED = Value
  }

  case class ChunkStatus(status: Status.Value, time: Long, chunk: Chunk, writingChunk: Set[ActorRef] = Set.empty,
                         hasChunk: Set[ActorRef] = Set.empty, waitingChunk: Set[ActorRef] = Set.empty)

  def apply(config: StorageConfig, storages: StorageTracker,
            log: LoggingAdapter)(implicit context: ActorContext): ChunksTracker = {
    new ChunksTracker(config, storages, log)
  }
}

private[actors] final class ChunksTracker(config: StorageConfig, storages: StorageTracker, log: LoggingAdapter)(implicit context: ActorContext) {
  import ChunksTracker._

  // -----------------------------------------------------------------------
  // State
  // -----------------------------------------------------------------------
  private[this] implicit val sender: ActorRef = context.self
  private[this] val chunks = mutable.AnyRefMap[ByteString, ChunkStatus]()

  // -----------------------------------------------------------------------
  // Read/write
  // -----------------------------------------------------------------------
  def readChunk(chunk: Chunk, receiver: ActorRef): Option[ChunkStatus] = {
    val statusOption = getChunk(chunk)
    statusOption match {
      case Some(status) ⇒
        if (!Utils.isSameChunk(status.chunk, chunk)) {
          receiver ! ReadChunk.Failure(chunk, new IllegalArgumentException(s"Chunks conflict: ${status.chunk} / $chunk"))
        } else if (status.chunk.data.encrypted.nonEmpty) {
          log.debug("Chunk extracted from cache: {}", status.chunk)
          val data = status.chunk.data.encrypted
          val source = Source.single(data)
            .mapMaterializedValue(_ ⇒ Future.successful(IOResult(data.length, Success(Done))))
          receiver ! ReadChunk.Success(status.chunk, source)
        } else {
          storages.forRead(status).headOption match {
            case Some(dispatcher) ⇒
              log.debug("Reading chunk from {}: {}", dispatcher, chunk)
              dispatcher.tell(ReadChunk(chunk), receiver)

            case None ⇒
              receiver ! ReadChunk.Failure(chunk, new IllegalArgumentException("Chunk unavailable"))
          }
        }

      case None ⇒
        receiver ! ReadChunk.Failure(chunk, new IllegalArgumentException("Chunk not found"))
    }
    statusOption
  }

  def writeChunk(chunk: Chunk, receiver: ActorRef): ChunkStatus = {
    require(chunk.nonEmpty)
    getChunk(chunk) match {
      case Some(stored) if stored.status == Status.STORED ⇒
        val chunkWithData = if (Utils.isSameChunk(stored.chunk, chunk) && chunk.data.encrypted.nonEmpty) {
          chunk
        } else {
          val encryptor = EncryptionModule(stored.chunk.encryption.method)
          stored.chunk.copy(data = chunk.data.copy(encrypted = encryptor.encrypt(chunk.data.plain, stored.chunk.encryption)))
        }
        receiver ! WriteChunk.Success(chunkWithData, chunkWithData)
        log.debug("Chunk restored from index, write skipped: {}", chunkWithData)
        stored.copy(chunk = chunkWithData)

      case Some(pending) if pending.status == Status.PENDING ⇒
        context.watch(receiver)
        log.debug("Already writing chunk, added to queue: {}", chunk)
        putStatus(pending.copy(waitingChunk = pending.waitingChunk + receiver))

      case None ⇒
        context.watch(receiver)
        val status = ChunkStatus(Status.PENDING, Utils.timestamp, chunk, waitingChunk = Set(receiver))
        val written = writeChunkToStorages(status)
        if (written.isEmpty) {
          log.warning("No storages available for write: {}", chunk)
        } else {
          log.debug("Writing chunk to {}: {}", written, chunk)
        }
        putStatus(status.copy(writingChunk = written))
    }
  }

  def retryPendingChunks(): Unit = {
    chunks.foreachValue { status ⇒
      if (status.status == Status.PENDING && status.hasChunk.isEmpty) {
        val written = writeChunkToStorages(status)
        if (written.nonEmpty) {
          log.debug("Retrying chunk write to {}: {}", written, status)
          putStatus(status.copy(hasChunk = written))
        }
      }
    }
  }

  // -----------------------------------------------------------------------
  // Update state
  // -----------------------------------------------------------------------
  def registerChunk(dispatcher: ActorRef, chunk: Chunk): ChunkStatus = {
    getChunk(chunk) match {
      case Some(status) if !Utils.isSameChunk(status.chunk, chunk) ⇒
        log.error("Chunk conflict: {} / {}", status.chunk, chunk)
        status

      case Some(status)  ⇒
        if (status.status == Status.PENDING) { // Chunk is pending
          val newStatus = status.copy(writingChunk = status.writingChunk - dispatcher,
            hasChunk = status.hasChunk + dispatcher)
          val needWrites = math.max(0, config.replicationFactor - newStatus.hasChunk.size)
          if (needWrites == 0) {
            require(status.chunk.data.nonEmpty)
            log.debug("Resolved pending chunk: {}", chunk)
            status.waitingChunk.foreach(_ ! WriteChunk.Success(status.chunk, status.chunk))
            putStatus(newStatus.copy(status = Status.STORED,
              chunk = status.chunk.withoutData, waitingChunk = Set.empty))
          } else {
            log.debug("Need {} more writes for {}", needWrites, chunk)
            putStatus(newStatus)
          }
        } else if (!status.hasChunk.contains(dispatcher)) {
          log.debug("Chunk duplicate found on {}: {}", dispatcher, chunk)
          putStatus(status.copy(writingChunk = status.writingChunk - dispatcher, hasChunk = status.hasChunk + dispatcher))
        } else {
          status
        }

      case None ⇒ // Chunk first seen
        putStatus(ChunkStatus(Status.STORED, Utils.timestamp, chunk.withoutData, hasChunk = Set(dispatcher)))
    }
  }

  def unregister(dispatcher: ActorRef): Unit = {
    chunks.foreachValue(removeActorRef(_, dispatcher))
  }

  def unregisterChunk(dispatcher: ActorRef, chunk: Chunk): Unit = {
    getChunk(chunk) match {
      case Some(status) ⇒
        if (Utils.isSameChunk(status.chunk, chunk)) {
          removeActorRef(status, dispatcher)
        } else {
          log.warning("Unknown chunk deleted: {} (existing: {})", chunk, status.chunk)
        }

      case None ⇒
        log.debug("Chunk not found: {}", chunk)
    }
  }

  def update(dispatcher: ActorRef, diff: ChunkIndexDiff): Unit = {
    diff.deletedChunks.foreach(unregisterChunk(dispatcher, _))
    diff.newChunks.foreach(registerChunk(dispatcher, _))
  }

  // -----------------------------------------------------------------------
  // Internal functions
  // -----------------------------------------------------------------------
  private[this] def getChunk(chunk: Chunk): Option[ChunkStatus] = {
    chunks.get(config.chunkKey(chunk))
  }

  private[this] def putStatus(status: ChunkStatus): ChunkStatus = {
    chunks += config.chunkKey(status.chunk) → status
    status
  }

  private[this] def removeStatus(status: ChunkStatus): Option[ChunkStatus] = {
    chunks.remove(config.chunkKey(status.chunk))
  }

  private[this] def writeChunkToStorages(status: ChunkStatus): Set[ActorRef] = {
    require(status.chunk.data.nonEmpty, "Chunks is empty")
    val writingCount = status.writingChunk.size + status.hasChunk.size
    val availableStorages = storages.forWrite(status)
    val selectedStorages = if (writingCount > 0) {
      availableStorages.take(config.replicationFactor - writingCount)
    } else {
      Utils.takeOrAll(availableStorages, config.replicationFactor)
    }
    selectedStorages.foreach(_ ! WriteChunk(status.chunk))
    selectedStorages.toSet
  }

  private[this] def removeActorRef(status: ChunkStatus, actor: ActorRef): Unit = {
    if (!status.hasChunk.contains(actor) && !status.writingChunk.contains(actor) &&
      !status.waitingChunk.contains(actor)) return

    val newStatus = status.copy(writingChunk = status.writingChunk - actor,
      hasChunk = status.hasChunk - actor, waitingChunk = status.waitingChunk - actor)
    if (status.status == Status.STORED && newStatus.hasChunk.isEmpty) {
      log.warning("Chunk is lost: {}", newStatus.chunk)
      removeStatus(status)
    } else if (status.status == Status.PENDING && newStatus.waitingChunk.isEmpty) {
      log.warning("Chunk write cancelled: {}", newStatus.chunk)
      removeStatus(status)
    } else {
      putStatus(newStatus)
    }
  }
}