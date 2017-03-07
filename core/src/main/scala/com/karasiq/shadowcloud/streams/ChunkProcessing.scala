package com.karasiq.shadowcloud.streams

import akka.NotUsed
import akka.stream.FlowShape
import akka.stream.scaladsl.{Flow, GraphDSL, Sink, ZipWith}
import akka.util.ByteString
import com.karasiq.shadowcloud.config.{AppConfig, CryptoConfig, ParallelismConfig}
import com.karasiq.shadowcloud.crypto._
import com.karasiq.shadowcloud.index.Chunk
import com.karasiq.shadowcloud.providers.ModuleRegistry

import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps

object ChunkProcessing {
  def apply(modules: ModuleRegistry, crypto: CryptoConfig,
            parallelism: ParallelismConfig)(implicit ec: ExecutionContext): ChunkProcessing = {
    new ChunkProcessing(modules, crypto, parallelism)
  }

  def apply(config: AppConfig)(implicit ec: ExecutionContext): ChunkProcessing = {
    apply(ModuleRegistry(config), config.crypto, config.parallelism)
  }
}

class ChunkProcessing(val modules: ModuleRegistry, val crypto: CryptoConfig, val parallelism: ParallelismConfig)(implicit ec: ExecutionContext) {
  type ChunkFlow = Flow[Chunk, Chunk, NotUsed]

  def generateKey(method: EncryptionMethod = crypto.encryption.chunks): ChunkFlow = {
    Flow.fromGraph(GraphDSL.create() { implicit builder ⇒
      import GraphDSL.Implicits._
      val keys = builder.add(ChunkKeyStream(modules, method))
      val chunkWithKey = builder.add(ZipWith((key: EncryptionParameters, chunk: Chunk) ⇒ chunk.copy(encryption = key)))
      keys ~> chunkWithKey.in0
      FlowShape(chunkWithKey.in1, chunkWithKey.out)
    })
  }

  def encrypt: ChunkFlow = parallelFlow(parallelism.encryption) { chunk ⇒
    require(chunk.data.plain.nonEmpty)
    val module = modules.encryptionModule(chunk.encryption.method)
    chunk.copy(data = chunk.data.copy(encrypted = module.encrypt(chunk.data.plain, chunk.encryption)))
  }

  def createHashes(method: HashingMethod = crypto.hashing.chunks,
                   hashCiphertext: Boolean = crypto.hashing.hashCiphertext): ChunkFlow = parallelFlow(parallelism.hashing) { chunk ⇒
    val module = modules.hashingModule(method)
    val size = chunk.data.plain.length
    val hash = if (chunk.data.plain.nonEmpty) module.createHash(chunk.data.plain) else ByteString.empty
    val encSize = chunk.data.encrypted.length
    val encHash = if (hashCiphertext && chunk.data.encrypted.nonEmpty) module.createHash(chunk.data.encrypted) else ByteString.empty
    chunk.copy(checksum = chunk.checksum.copy(method, size, hash, encSize, encHash))
  }

  def decrypt: ChunkFlow = parallelFlow(parallelism.encryption) { chunk ⇒
    require(chunk.data.encrypted.nonEmpty)
    val decryptor = modules.encryptionModule(chunk.encryption.method)
    val decryptedData = decryptor.decrypt(chunk.data.encrypted, chunk.encryption)
    chunk.copy(data = chunk.data.copy(plain = decryptedData))
  }

  def verify: ChunkFlow = parallelFlow(parallelism.hashing) { chunk ⇒
    val hasher = modules.hashingModule(chunk.checksum.method)
    if (chunk.checksum.hash.nonEmpty && hasher.createHash(chunk.data.plain) != chunk.checksum.hash) {
      throw new IllegalArgumentException(s"Chunk plaintext checksum not match: $chunk")
    } else if (chunk.checksum.encryptedHash.nonEmpty && hasher.createHash(chunk.data.encrypted) != chunk.checksum.encryptedHash) {
      throw new IllegalArgumentException(s"Chunk ciphertext checksum not match: $chunk")
    } else if ((chunk.data.plain.nonEmpty && chunk.checksum.size != chunk.data.plain.length) ||
      (chunk.data.encrypted.nonEmpty && chunk.checksum.encryptedSize != chunk.data.encrypted.length)) {
      throw new IllegalArgumentException(s"Chunk sizes not match: $chunk")
    } else {
      chunk
    }
  }

  def beforeWrite(encryption: EncryptionMethod = crypto.encryption.chunks,
                  hashing: HashingMethod = crypto.hashing.chunks,
                  hashCiphertext: Boolean = crypto.hashing.hashCiphertext): ChunkFlow = {
    generateKey(encryption).via(encrypt).via(createHashes(hashing, hashCiphertext))
  }

  def afterRead: ChunkFlow = {
    decrypt.via(verify)
  }

  def index(hashingMethod: HashingMethod = crypto.hashing.files,
            hashCiphertext: Boolean = crypto.hashing.hashCiphertext): Sink[Chunk, Future[FileIndexer.Result]] = {
    Sink.fromGraph(FileIndexer(modules, hashingMethod, hashCiphertext).async)
  }

  protected def parallelFlow(parallelism: Int)(func: Chunk ⇒ Chunk): ChunkFlow = {
    require(parallelism > 0)
    Flow[Chunk].mapAsync(parallelism)(chunk ⇒ Future(func(chunk)))
  }
}
