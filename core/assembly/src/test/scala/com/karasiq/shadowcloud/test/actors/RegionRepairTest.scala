package com.karasiq.shadowcloud.test.actors

import scala.concurrent.duration._
import scala.language.postfixOps

import akka.stream.scaladsl.Keep
import akka.stream.testkit.scaladsl.{TestSink, TestSource}
import org.scalatest.FlatSpecLike

import com.karasiq.shadowcloud.actors.ChunkIODispatcher.ChunkPath
import com.karasiq.shadowcloud.config.ConfigProps
import com.karasiq.shadowcloud.storage.props.StorageProps
import com.karasiq.shadowcloud.storage.props.StorageProps.Quota
import com.karasiq.shadowcloud.storage.replication.ChunkWriteAffinity
import com.karasiq.shadowcloud.streams.region.RegionRepairStream
import com.karasiq.shadowcloud.test.utils.{SCExtensionSpec, TestUtils}

class RegionRepairTest extends SCExtensionSpec with FlatSpecLike {
  val testRegionId = "repairTestRegion"
  val testStorage1 = "repairTestS1"
  val testStorage2 = "repairTestS2"

  "Region repair stream" should "repair chunks" in {
    registerRegionAndStorages()

    val chunk = TestUtils.testChunk
    sc.ops.region.writeChunk(testRegionId, chunk).futureValue shouldBe chunk
    sc.ops.region.synchronize(testRegionId)
    
    expectNoMsg(500 millis) // Wait for synchronization

    val (repairStream, repairResult) = TestSource.probe[RegionRepairStream.Request]
      .alsoTo(RegionRepairStream(sc.config.parallelism, sc.ops.region))
      .mapAsync(1)(_.result.future)
      .toMat(TestSink.probe)(Keep.both)
      .run()

    repairStream.sendNext(RegionRepairStream.Request(testRegionId,
      RegionRepairStream.Strategy.SetAffinity(ChunkWriteAffinity(Seq(testStorage1, testStorage2)))))

    val repaired = repairResult.requestNext()
    repaired shouldBe Seq(chunk)

    repairStream.sendComplete()
    repairResult.request(1)
    repairResult.expectComplete()

    val chunkPath = ChunkPath(testRegionId, chunk.checksum.hash)
    sc.ops.storage.readChunk(testStorage1, chunkPath, chunk).futureValue shouldBe chunk
    sc.ops.storage.readChunk(testStorage2, chunkPath, chunk).futureValue shouldBe chunk
  }

  private[this] def registerRegionAndStorages(): Unit = {
    sc.ops.supervisor.addRegion(testRegionId, sc.configs.regionConfig(testRegionId))
    sc.ops.supervisor.addStorage(testStorage1, StorageProps.inMemory.copy(quota = Quota(ConfigProps.toConfig(ConfigProps("use-space-percent" → 33)))))
    sc.ops.supervisor.addStorage(testStorage2, StorageProps.inMemory)
    sc.ops.supervisor.register(testRegionId, testStorage1)
    sc.ops.supervisor.register(testRegionId, testStorage2)
    expectNoMsg(100 millis)
  }
}