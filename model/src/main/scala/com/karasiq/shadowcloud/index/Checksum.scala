package com.karasiq.shadowcloud.index

import scala.language.postfixOps

import akka.util.ByteString

import com.karasiq.shadowcloud.crypto.HashingMethod
import com.karasiq.shadowcloud.index.utils.HasEmpty
import com.karasiq.shadowcloud.utils.{HexString, MemorySize}

case class Checksum(method: HashingMethod = HashingMethod.default, encMethod: HashingMethod = HashingMethod.default,
                    size: Long = 0, hash: ByteString = ByteString.empty, encSize: Long = 0,
                    encHash: ByteString = ByteString.empty) extends HasEmpty {
  def isEmpty: Boolean = {
    size == 0 && hash.isEmpty && encSize == 0 && encHash.isEmpty
  }

  override def hashCode(): Int = {
    (method, size, hash).hashCode()
  }

  override def equals(obj: scala.Any): Boolean = obj match {
    case Checksum(method1, encMethod1, size1, hash1, encSize1, encHash1) ⇒
      method == method1 &&
        size == size1 &&
        hash == hash1 &&
        ((encSize == 0 || encSize1 == 0) || encSize == encSize1) &&
        ((encHash.isEmpty || encHash1.isEmpty) || (encMethod == encMethod1 && encHash == encHash1))

    case _ ⇒
      false
  }

  override def toString: String = {
    def sizeAndHash(prefix: String, size: Long, hash: ByteString) = {
      if (size == 0 && hash.isEmpty) ""
      else if (hash.isEmpty) s"$prefix: ${MemorySize.toString(size)}"
      else s"$prefix: ${MemorySize.toString(size)} [${HexString.encode(hash)}]"
    }
    val methods = if (method == encMethod) {
      Seq(method.toString)
    } else {
      Seq(method.toString, encMethod.toString)
    }
    val plain = sizeAndHash("plain", size, hash)
    val encrypted = sizeAndHash("encrypted", encSize, encHash)

    s"Checksum(${(methods ++ Seq(plain, encrypted)).filter(_.nonEmpty).mkString(", ")})"
  }
}

object Checksum {
  val empty = Checksum()
}