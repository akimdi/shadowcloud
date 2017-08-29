package com.karasiq.shadowcloud.crypto

import scala.language.postfixOps

import akka.util.ByteString

@SerialVersionUID(0L)
case class SignParameters(method: SignMethod, publicKey: ByteString, privateKey: ByteString) {
  def readOnly: SignParameters = {
    copy(privateKey = ByteString.empty)
  }

  override def toString: String = {
    s"SignParameters($method, public: ${publicKey.length * 8} bits, private: ${privateKey.length * 8} bits)"
  }
}

object SignParameters {
  val empty = SignParameters(SignMethod.none, ByteString.empty, ByteString.empty)
}