// ver 1.0.1 | 2026-11-06 18:03
package io.tripsign.app.core

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A server-signed identity check (tripsign.beyondspark.kr/auth). It proves: at [claimedAtMs] someone in front of the car
 * [car] typed the code shown on a phone whose owner had just proved this e-mail (and gave this phone number).
 * It does not prove who sat in the driver's seat.
 *
 * Text: tsa1~sid~car~purpose~target~emailHash~phoneHash~emailMasked~phoneMasked~verifiedAtMs~claimedAtMs,
 * signed by the server key (ECDSA P-256, SHA-256, DER). Stored in a ledger AUTH entry as "<text>\u001F<signature>".
 */
data class AuthProof(
    val text: String,
    val signature: String,
    val sid: String,
    val car: String,
    val purpose: String,
    val target: String,
    val emailHash: String,
    val phoneHash: String,
    val emailMasked: String,
    val phoneMasked: String,
    val verifiedAtMs: Long,
    val claimedAtMs: Long
) {
    /** One person = one verified e-mail. Used to find the registered user who confirmed a trip. */
    val personId: String get() = emailHash

    fun note(): String = text + Logbook.SEP + signature

    companion object {
        const val ENROLL = "enroll"
        const val CONFIRM = "confirm"

        /**
         * Public key of the TripSign auth server (SubjectPublicKeyInfo, Base64). Generated on the server at install;
         * the private key never leaves it. Replace together with the server key (tools: `tripsign_auth.py pubkey`).
         */
        const val SERVER_KEY_B64 = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEKxwVZDgFUdUunYyTGvxdkQXwptSY8oRKd0/i4mw6d7RPYKTFoybRwl1XvVEpd48+q2LWeBFOEm9/5bFOxJaWwg=="

        fun parse(text: String, signature: String): AuthProof? {
            val f = text.split("~")
            if (f.size != 11 || f[0] != "tsa1") return null
            val verified = f[9].toLongOrNull() ?: return null
            val claimed = f[10].toLongOrNull() ?: return null
            return AuthProof(text, signature, f[1], f[2], f[3], f[4], f[5], f[6], f[7], f[8], verified, claimed)
        }

        fun fromNote(note: String): AuthProof? {
            val i = note.lastIndexOf(Logbook.SEP)
            if (i <= 0) return null
            return parse(note.substring(0, i), note.substring(i + 1))
        }

        fun publicKey(b64: String): PublicKey? = try {
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(b64)))
        } catch (e: Exception) {
            null
        }

        /** True when [signature] (Base64 DER) is the server's signature over [text]. */
        fun signatureOk(text: String, signature: String, key: PublicKey?): Boolean {
            if (key == null) return false
            return try {
                val s = Signature.getInstance("SHA256withECDSA")
                s.initVerify(key)
                s.update(text.toByteArray(Charsets.UTF_8))
                s.verify(Base64.getDecoder().decode(signature))
            } catch (e: Exception) {
                false
            }
        }

        /** The car id sent to the server: first 16 bytes of SHA-256(public key), hex. */
        fun carId(publicKeyEncoded: ByteArray): String {
            val d = MessageDigest.getInstance("SHA-256").digest(publicKeyEncoded)
            return d.take(16).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }

        private fun hmacHex(key: ByteArray, text: String): String {
            val m = Mac.getInstance("HmacSHA256")
            m.init(SecretKeySpec(key, "HmacSHA256"))
            return m.doFinal(text.toByteArray(Charsets.US_ASCII)).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }

        /** QR step: the code on the car screen changes every [stepS] seconds. */
        fun step(nowMs: Long, stepS: Int): Long = nowMs / 1000L / stepS

        fun qrMac(key: ByteArray, sid: String, step: Long): String = hmacHex(key, "$sid.$step").take(16)

        fun claimMac(key: ByteArray, sid: String, carCode: String): String = hmacHex(key, "claim.$sid.$carCode").take(32)

        /** What the car shows as a QR. The phone page reads it from the address fragment (never sent as a query). */
        fun qrUrl(base: String, sid: String, step: Long, key: ByteArray): String = "$base#s=$sid.$step.${qrMac(key, sid, step)}"
    }
}
