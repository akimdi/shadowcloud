package com.karasiq.shadowcloud.utils

import scala.collection.TraversableLike
import scala.concurrent.duration.FiniteDuration
import scala.language.{higherKinds, postfixOps}

import akka.util.ByteString
import com.typesafe.config.ConfigFactory

import com.karasiq.shadowcloud.index.{Chunk, Path}

private[shadowcloud] object Utils {
  // -----------------------------------------------------------------------
  // Paths
  // -----------------------------------------------------------------------
  val internalFolderPath = Path.root / ".$sc-internal"
  
  // -----------------------------------------------------------------------
  // Time
  // -----------------------------------------------------------------------
  @inline
  def timestamp: Long = {
    System.currentTimeMillis()
  }

  @inline
  def toScalaDuration(duration: java.time.Duration): FiniteDuration = {
    FiniteDuration(duration.toNanos, scala.concurrent.duration.NANOSECONDS)
  }

  // -----------------------------------------------------------------------
  // toString() utils
  // -----------------------------------------------------------------------
  def printValues[T](values: Traversable[T], limit: Int = 10): String = {
    val sb = new StringBuilder(100)
    val size = values.size
    values.take(limit).foreach(v ⇒ sb.append(v.toString))
    if (size > limit) sb.append(", (").append(size - limit).append(" more)")
    sb.result()
  }

  def printHashes(hashes: Traversable[ByteString], limit: Int = 20): String = {
    if (hashes.isEmpty) return ""
    val size = hashes.size
    val sb = new StringBuilder(math.min(limit, size) * (hashes.head.length * 2) + 10)
    hashes.filter(_.nonEmpty).take(limit).foreach { hash ⇒
      if (sb.nonEmpty) sb.append(", ")
      sb.append(HexString.encode(hash))
    }
    if (size > limit) sb.append(", (").append(size - limit).append(" more)")
    sb.result()
  }

  def printChunkHashes(chunks: Traversable[Chunk], limit: Int = 20): String = {
    printHashes(chunks.map(_.checksum.hash))
  }

  // -----------------------------------------------------------------------
  // Misc
  // -----------------------------------------------------------------------
  @inline
  def isSameChunk(chunk: Chunk, chunk1: Chunk): Boolean = {
    // chunk.withoutData == chunk1.withoutData
    chunk == chunk1
  }

  @inline
  def takeOrAll[T, Col[`T`] <: TraversableLike[T, Col[T]]](all: Col[T], count: Int): Col[T] = {
    if (count > 0) all.take(count) else all
  }

  def getFileExtension(path: String): String = {
    def indexOfExtension(path: String): Option[Int] = {
      def indexOfLastSeparator(filename: String): Int = math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'))
      val lastDot = path.lastIndexOf('.')
      val lastSeparator = indexOfLastSeparator(path)
      Some(lastDot).filterNot(lastSeparator > _)
    }
    indexOfExtension(path).fold("")(index ⇒ path.substring(index + 1))
  }

  def toSafeIdentifier(str: String): String = {
    str.replaceAll("[^A-Za-z0-9-_]", "_")
  }

  def uniqueActorName(name: String): String = {
    toSafeIdentifier(name) + "-" + java.lang.Long.toHexString(System.nanoTime())
  }

  val emptyConfig = ConfigFactory.empty()
}
