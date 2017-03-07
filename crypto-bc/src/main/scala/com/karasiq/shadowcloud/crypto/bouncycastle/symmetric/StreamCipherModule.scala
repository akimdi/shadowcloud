package com.karasiq.shadowcloud.crypto.bouncycastle.symmetric

import akka.util.ByteString
import com.karasiq.shadowcloud.crypto.bouncycastle.internal.RandomKeys
import com.karasiq.shadowcloud.crypto.{EncryptionMethod, EncryptionParameters, StreamEncryptionModule}
import org.bouncycastle.crypto.StreamCipher
import org.bouncycastle.crypto.engines.{ChaChaEngine, Salsa20Engine, XSalsa20Engine}
import org.bouncycastle.crypto.params.{KeyParameter, ParametersWithIV}

import scala.language.postfixOps

private[bouncycastle] object StreamCipherModule {
  def Salsa20(method: EncryptionMethod = EncryptionMethod("Salsa20", 256)): StreamCipherModule = {
    new StreamCipherModule(method, new Salsa20Engine(), 8)
  }

  def XSalsa20(method: EncryptionMethod = EncryptionMethod("XSalsa20", 256)): StreamCipherModule = {
    new StreamCipherModule(method, new XSalsa20Engine(), 24)
  }

  def ChaCha20(method: EncryptionMethod = EncryptionMethod("ChaCha20", 256)): StreamCipherModule = {
    new StreamCipherModule(method, new ChaChaEngine(), 8)
  }
}

private[bouncycastle] final class StreamCipherModule(protected val method: EncryptionMethod,
                                                     private[this] val cipher: StreamCipher,
                                                     protected val nonceSize: Int)
  extends StreamEncryptionModule with RandomKeys {

  def init(encrypt: Boolean, parameters: EncryptionParameters): Unit = {
    val parameters1 = parameters.symmetric
    val bcParameters = new ParametersWithIV(new KeyParameter(parameters1.key.toArray), parameters1.nonce.toArray)
    cipher.init(encrypt, bcParameters)
  }

  def process(data: ByteString): ByteString = {
    val inArray = data.toArray
    val length = inArray.length
    val outArray = new Array[Byte](length)
    cipher.processBytes(inArray, 0, inArray.length, outArray, 0)
    ByteString(outArray)
  }

  def finish(): ByteString = {
    ByteString.empty
  }
}