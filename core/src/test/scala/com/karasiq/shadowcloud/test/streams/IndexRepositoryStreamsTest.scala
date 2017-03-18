package com.karasiq.shadowcloud.test.streams

import java.nio.file.Files

import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.testkit.scaladsl.{TestSink, TestSource}
import com.karasiq.shadowcloud.index.diffs.IndexDiff
import com.karasiq.shadowcloud.storage._
import com.karasiq.shadowcloud.storage.utils.{IndexIOResult, IndexRepositoryStreams}
import com.karasiq.shadowcloud.test.utils.{ActorSpec, TestUtils}
import org.scalatest.FlatSpecLike

import scala.language.postfixOps

class IndexRepositoryStreamsTest extends ActorSpec with FlatSpecLike {
  "In-memory repository" should "store diff" in {
    testRepository(Repositories.inMemory)
  }

  "File repository" should "store diff" in {
    testRepository(Repositories.fromDirectory(Files.createTempDirectory("irp-test")).subRepository("default"))
  }

  it should "validate path" in {
    intercept[IllegalArgumentException](Repositories.fromDirectory(Files.createTempFile("irp-test", "file")))
  }

  private[this] def testRepository(repository: BaseRepository): Unit = {
    val diff = TestUtils.randomDiff
    val testRepository = RepositoryKeys.toLong(repository)

    // Write diff
    val streams = IndexRepositoryStreams.gzipped
    val (write, writeResult) = TestSource.probe[(Long, IndexDiff)]
      .via(streams.write(testRepository))
      .toMat(TestSink.probe)(Keep.both)
      .run()
    write.sendNext((diff.time, diff))
    val IndexIOResult(diff.time, `diff`, StorageIOResult.Success(_, _)) = writeResult.requestNext()
    write.sendComplete()
    writeResult.expectComplete()

    // Enumerate diffs
    val keys = testRepository.keys.runWith(TestSink.probe)
    keys.requestNext(diff.time)
    keys.request(1)
    keys.expectComplete()

    // Read diff
    val (read, readResult) = TestSource.probe[Long]
      .via(streams.read(testRepository))
      .toMat(TestSink.probe)(Keep.both)
      .run()
    read.sendNext(diff.time)
    val IndexIOResult(diff.time, `diff`, StorageIOResult.Success(_, diffBytes)) = readResult.requestNext()
    diffBytes should not be 0
    read.sendComplete()
    readResult.expectComplete()

    // Rewrite error
    val rewriteResult = Source.single(TestUtils.randomBytes(200))
      .runWith(testRepository.write(diff.time))

    whenReady(rewriteResult) { result ⇒
      result.isFailure shouldBe true
    }

    val deleteResult = Source.single(diff.time)
      .via(streams.delete(testRepository))
      .runWith(Sink.head)

    whenReady(deleteResult) { result ⇒
      val IndexIOResult(key, _, StorageIOResult.Success(_, count)) = result 
      key shouldBe diff.time
      count shouldBe diffBytes
      val keys = testRepository.keys.runWith(TestSink.probe)
      keys.request(1)
      keys.expectComplete()
    }
  }
}