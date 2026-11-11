// ver 1.0.0 | 2026-11-04 15:10
package io.tripsign.app.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.tripsign.app.core.Signer
import io.tripsign.app.core.Verifier
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PublicKey
import java.security.Signature
import java.util.Base64

/** Device-bound EC P-256 key in Android Keystore. The private key never leaves the keystore. */
class KeystoreKeys : Signer, Verifier {
    private val alias = "tripsign-ledger-v1"
    private val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Synchronized
    private fun ensureEntry(): KeyStore.PrivateKeyEntry {
        if (!keyStore.containsAlias(alias)) {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            generator.initialize(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build()
            )
            generator.generateKeyPair()
        }
        return keyStore.getEntry(alias, null) as KeyStore.PrivateKeyEntry
    }

    val publicKey: PublicKey get() = ensureEntry().certificate.publicKey

    fun publicKeyBase64(): String = Base64.getEncoder().encodeToString(publicKey.encoded)

    override fun sign(data: ByteArray): String {
        val s = Signature.getInstance("SHA256withECDSA")
        s.initSign(ensureEntry().privateKey)
        s.update(data)
        return Base64.getEncoder().encodeToString(s.sign())
    }

    override fun verify(data: ByteArray, signature: String): Boolean = try {
        val s = Signature.getInstance("SHA256withECDSA")
        s.initVerify(publicKey)
        s.update(data)
        s.verify(Base64.getDecoder().decode(signature))
    } catch (e: Exception) {
        false
    }
}
