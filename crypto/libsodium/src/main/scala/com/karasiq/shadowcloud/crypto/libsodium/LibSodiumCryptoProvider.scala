package com.karasiq.shadowcloud.crypto.libsodium

import com.karasiq.shadowcloud.crypto.libsodium.hashing.{Blake2bModule, MultiPartHashModule}
import com.karasiq.shadowcloud.crypto.libsodium.internal._
import com.karasiq.shadowcloud.crypto.libsodium.symmetric._
import com.karasiq.shadowcloud.crypto.{EncryptionMethod, HashingMethod}
import com.karasiq.shadowcloud.providers.CryptoProvider

import scala.language.postfixOps

final class LibSodiumCryptoProvider extends CryptoProvider {
  override val hashingAlgorithms: Set[String] = ifLoaded(super.hashingAlgorithms) {
    Set("SHA256", "SHA512", "Blake2b")
  }

  override def hashing: HashingPF = ifLoaded(super.hashing) {
    case method @ HashingMethod("SHA256", _, _, _) ⇒
      MultiPartHashModule.SHA256(method)

    case method @ HashingMethod("SHA512", _, _, _) ⇒
      MultiPartHashModule.SHA512(method)

    case method @ HashingMethod("Blake2b", _, _, _) ⇒
      Blake2bModule(method)
  }

  override def encryptionAlgorithms: Set[String] = ifLoaded(super.encryptionAlgorithms) {
    @inline
    def onlyIf(cond: Boolean)(algorithms: String*): Seq[String] = {
      if (cond) algorithms else Nil
    }

    Set("XSalsa20/Poly1305", "ChaCha20/Poly1305", "Salsa20", "XSalsa20", "ChaCha20") ++
      onlyIf(LSUtils.aes256GcmAvailable)("AES/GCM")
  }

  override def encryption: EncryptionPF = ifLoaded(super.encryption) {
    case method @ EncryptionMethod("XSalsa20/Poly1305", 256, _, _, _) ⇒
      SecretBoxModule(method)

    case method @ EncryptionMethod("ChaCha20/Poly1305", 256, _, _, _)  ⇒
      AEADCipherModule.ChaCha20_Poly1305(method)

    case method @ EncryptionMethod("AES/GCM", 256, _, _, _) if LSUtils.aes256GcmAvailable ⇒
      AEADCipherModule.AES_GCM(method)

    case method @ EncryptionMethod("Salsa20", 256, _, _, _) ⇒
      Salsa20Module(method)

    case method @ EncryptionMethod("XSalsa20", 256, _, _, _) ⇒
      XSalsa20Module(method)

    case method @ EncryptionMethod("ChaCha20", 256, _, _, _) ⇒
      ChaCha20Module(method)
  }

  @inline
  private[this] def ifLoaded[T](empty: ⇒ T)(value: ⇒ T): T = {
    if (LSUtils.libraryAvailable) value else empty
  }
}