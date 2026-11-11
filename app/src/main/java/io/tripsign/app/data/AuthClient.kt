// ver 1.0.0 | 2026-11-06 16:48
package io.tripsign.app.data

import io.tripsign.app.core.AuthProof
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * Car side of the phone check (tripsign.beyondspark.kr/auth). Blocking calls: run them off the main thread.
 * Only the car id (a hash of the ledger key), the purpose and the target go to the server; no trip data.
 */
object AuthClient {
    const val BASE = "https://tripsign.beyondspark.kr"
    const val PAGE = "$BASE/auth/"

    class Session(val sid: String, val key: ByteArray, val ttlS: Int, val stepS: Int, val startedMs: Long, val car: String, val purpose: String, val target: String)

    sealed interface Result<out T> {
        data class Ok<T>(val value: T) : Result<T>
        /** [code]: server error name (ERR_…), or ERR_NET when the car has no connection. [left]: tries left, -1 if not given. */
        data class Fail(val code: String, val left: Int = -1) : Result<Nothing>
    }

    private fun post(path: String, body: JSONObject): Pair<Int, JSONObject> {
        val c = (URL("$BASE/auth/api/$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8_000
            readTimeout = 12_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        try {
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "{}"
            return code to (try { JSONObject(text) } catch (e: Exception) { JSONObject().put("error", "ERR_SERVER") })
        } finally {
            c.disconnect()
        }
    }

    fun start(carId: String, purpose: String, target: String): Result<Session> = try {
        val (code, j) = post("session", JSONObject().put("car", carId).put("purpose", purpose).put("target", target))
        if (code != 200) Result.Fail(j.optString("error", "ERR_SERVER"))
        else Result.Ok(Session(j.getString("sid"), Base64.getDecoder().decode(j.getString("key")), j.optInt("ttl", 600), j.optInt("step", 10), System.currentTimeMillis(), carId, purpose, target))
    } catch (e: Exception) {
        DiagLog.w("본인확인 시작 실패: ${e.javaClass.simpleName}")
        Result.Fail("ERR_NET")
    }

    /**
     * Sends the code the phone showed. The server returns its signed proof; it is checked here against the built-in
     * server key before anything is written (a proof from anywhere else is refused).
     */
    fun claim(s: Session, carCode: String): Result<AuthProof> = try {
        val (code, j) = post("claim", JSONObject().put("sid", s.sid).put("carCode", carCode).put("mac", AuthProof.claimMac(s.key, s.sid, carCode)))
        if (code != 200) Result.Fail(j.optString("error", "ERR_SERVER"), j.optInt("left", -1))
        else {
            val proof = AuthProof.parse(j.getString("a"), j.getString("s"))
            val key = AuthProof.publicKey(AuthProof.SERVER_KEY_B64)
            when {
                proof == null -> Result.Fail("ERR_PROOF")
                proof.sid != s.sid || proof.car != s.car || proof.purpose != s.purpose || proof.target != s.target -> Result.Fail("ERR_PROOF")
                !AuthProof.signatureOk(proof.text, proof.signature, key) -> Result.Fail("ERR_PROOF")
                else -> Result.Ok(proof)
            }
        }
    } catch (e: Exception) {
        DiagLog.w("본인확인 확인번호 실패: ${e.javaClass.simpleName}")
        Result.Fail("ERR_NET")
    }
}
