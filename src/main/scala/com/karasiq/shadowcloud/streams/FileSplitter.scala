package com.karasiq.shadowcloud.streams

import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.util.ByteString
import com.karasiq.shadowcloud.crypto.{HashingMethod, HashingModule}
import com.karasiq.shadowcloud.index.{Checksum, Chunk, Data}

import scala.language.postfixOps

/**
  * Splits input data to fixed size chunks with hash
  * @param chunkSize Output chunk size
  * @param hashingMethod Hashing method
  */
class FileSplitter(chunkSize: Int, hashingMethod: HashingMethod) extends GraphStage[FlowShape[ByteString, Chunk]] {
  val inBytes = Inlet[ByteString]("FileSplitter.inBytes")
  val outChunks = Outlet[Chunk]("FileSplitter.outChunks")
  val shape = FlowShape(inBytes, outChunks)

  def createLogic(inheritedAttributes: Attributes) = new GraphStageLogic(shape) {
    val hashingModule = HashingModule(hashingMethod)
    var buffer = ByteString.empty

    def hashAndWrite(bytes: ByteString): Unit = {
      val sizeToHash = chunkSize - buffer.length
      if (sizeToHash > 0) {
        val bytesToHash = bytes.take(sizeToHash)
        hashingModule.update(bytesToHash)
      }
      buffer ++= bytes
    }

    def emitNextChunk(after: () ⇒ Unit = () ⇒ ()): Unit = {
      require(buffer.nonEmpty, "Buffer is empty")
      val (chunkBytes, nextChunkPart) = buffer.splitAt(chunkSize)

      // Create new chunk
      val chunk = Chunk(checksum = Checksum(hashingModule.method, chunkBytes.size, hashingModule.createHash()), data = Data(plain = chunkBytes))

      // Reset buffer
      buffer = ByteString.empty
      hashingModule.reset()
      if (nextChunkPart.nonEmpty) hashAndWrite(nextChunkPart)

      // Emit chunk
      emit(outChunks, chunk, after)
    }

    def emitLastChunk(): Unit = {
      if (buffer.nonEmpty)
        emitNextChunk(emitLastChunk)
      else
        complete(outChunks)
    }

    def processBuffer(): Unit = {
      if (buffer.length >= chunkSize) { // Emit finished chunk
        emitNextChunk()
      } else if (isClosed(inBytes)) { // Emit last part and close stream
        emitLastChunk()
      } else { // Pull more bytes
        pull(inBytes)
      }
    }

    setHandler(inBytes, new InHandler {
      def onPush(): Unit = {
        val bytes = grab(inBytes)
        if (bytes.nonEmpty) hashAndWrite(bytes)
        processBuffer()
      }

      @scala.throws[Exception](classOf[Exception])
      override def onUpstreamFinish(): Unit = {
        emitLastChunk()
      }
    })

    setHandler(outChunks, new OutHandler {
      def onPull(): Unit = {
        processBuffer()
      }
    })
  }
}
