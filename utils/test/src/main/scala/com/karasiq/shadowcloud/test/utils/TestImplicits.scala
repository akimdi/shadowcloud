package com.karasiq.shadowcloud.test.utils

import scala.language.postfixOps

import akka.util.ByteString

import com.karasiq.shadowcloud.index.Chunk
import com.karasiq.shadowcloud.utils.HexString

trait TestImplicits {
  implicit class ByteStringOps(private val bs: ByteString) {
    def toHexString: String = {
      HexString.encode(bs)
    }
  }

  implicit class ByteStringObjOps(private val bs: ByteString.type) {
    def fromHexString(hexString: String): ByteString = {
      HexString.decode(hexString)
    }

    def fromChunks(chunks: Seq[Chunk]): ByteString = {
      chunks.map(_.data.plain).fold(ByteString.empty)(_ ++ _)
    }

    def fromEncryptedChunks(chunks: Seq[Chunk]): ByteString = {
      chunks.map(_.data.encrypted).fold(ByteString.empty)(_ ++ _)
    }
  }
}