// ver 1.4.0 | 2026-11-07 13:25
package io.tripsign.app.data

import android.content.Context
import io.tripsign.app.core.AuthProof
import io.tripsign.app.core.Chain
import io.tripsign.app.core.ChainCheck
import io.tripsign.app.core.DistanceSource
import io.tripsign.app.core.Driver
import io.tripsign.app.core.Entry
import io.tripsign.app.core.Logbook
import io.tripsign.app.core.Purpose
import io.tripsign.app.core.RecordType
import io.tripsign.app.core.SealedEntry
import io.tripsign.app.core.Vehicle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Append-only ledger (ledger.jsonl). Every line is a signed, hash-chained entry.
 * There is no update or delete API: purpose changes are new PURPOSE entries.
 */
class TripRepository private constructor(val appContext: Context) {

    companion object {
        @Volatile
        private var instance: TripRepository? = null

        fun get(context: Context): TripRepository =
            instance ?: synchronized(this) {
                instance ?: TripRepository(context.applicationContext).also { instance = it }
            }
    }

    private val file = File(appContext.filesDir, "ledger.jsonl")
    val keys = KeystoreKeys()

    private val _entries = MutableStateFlow<List<SealedEntry>>(emptyList())
    val entries: StateFlow<List<SealedEntry>> = _entries

    private val _check = MutableStateFlow(ChainCheck(true, 0, Chain.GENESIS))
    val check: StateFlow<ChainCheck> = _check

    init {
        Settings.load(appContext)
        load()
    }

    @Synchronized
    private fun load() {
        val list = mutableListOf<SealedEntry>()
        if (file.exists()) {
            file.forEachLine { line ->
                if (line.isNotBlank()) {
                    try {
                        list += fromJson(JSONObject(line))
                    } catch (e: Exception) {
                        DiagLog.w("원장 줄 읽기 실패: ${e.message}")
                    }
                }
            }
        }
        _entries.value = list
        _check.value = Chain.verify(list, keys)
        DiagLog.w("원장 로드: ${list.size}건, 검증=${_check.value.ok} ${_check.value.reason}")
    }

    @Synchronized
    fun reverify(): ChainCheck {
        val c = Chain.verify(_entries.value, keys)
        _check.value = c
        return c
    }

    @Synchronized
    fun append(entry: Entry): SealedEntry {
        val current = _entries.value
        val sealed = Chain.seal(current.lastOrNull(), entry, System.currentTimeMillis(), keys)
        FileOutputStream(file, true).use { out ->
            out.write((toJson(sealed).toString() + "\n").toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        _entries.value = current + sealed
        val prev = _check.value
        _check.value = if (prev.ok) ChainCheck(true, current.size + 1, sealed.hash) else prev.copy(count = current.size + 1)
        DiagLog.w("기록 추가 #${sealed.seq} ${entry.type} ${entry.tripId} ${"%.3f".format(entry.distanceKm)}km ${entry.purpose ?: ""}")
        return sealed
    }

    /**
     * 0.5.2: a finished drive with the user who was logged in when it STARTED (form ④). The login ends with the trip,
     * so the next drive starts with "누가 운전하나요?" again. A 미인증 운행 carries [Logbook.NOTE_UNVERIFIED].
     */
    @Synchronized
    fun appendTrip(entry: Entry, driver: Driver?): SealedEntry {
        val sealed = append(entry)
        if (driver != null) append(Entry(type = RecordType.ASSIGN, tripId = entry.tripId, note = driver.encode()))
        Settings.logout(appContext)
        return sealed
    }

    /** The "who is driving?" warning was put on screen ([kind] = screen | notice). */
    fun appendWarn(kind: String, nowMs: Long = System.currentTimeMillis()): SealedEntry =
        append(Entry(type = RecordType.WARN, tripId = "W$nowMs", startMs = nowMs, endMs = nowMs, note = kind))

    /** 0.5.2g: "내가 아니다" on the 미인증 운행 card at a car start (does not say who pressed it: nobody is logged in yet). */
    fun appendDeny(tripId: String, nowMs: Long = System.currentTimeMillis()): SealedEntry =
        append(Entry(type = RecordType.DENY, tripId = tripId, startMs = nowMs, endMs = nowMs, note = Logbook.NOTE_NOT_ME))

    /** Newest finished drive, or null. */
    fun newestTripId(): String? = _entries.value.lastOrNull { it.entry.type == RecordType.TRIP }?.entry?.tripId

    private fun row(tripId: String) = Logbook.rows(_entries.value).firstOrNull { it.id == tripId }

    /**
     * The driver's own purpose choice: only in the newest trip's window, parked with no drive open (see
     * [Logbook.driverMayClassify]). Returns false when the window is closed (then only [classifyByAdmin]).
     */
    @Synchronized
    fun classifyByDriver(tripId: String, purpose: Purpose, nowMs: Long, parkedIdle: Boolean): Boolean {
        val r = row(tripId) ?: return false
        if (!Logbook.driverMayClassify(r, newestTripId(), nowMs, parkedIdle)) {
            DiagLog.w("분류 거절(운전자 창 닫힘) $tripId")
            return false
        }
        append(Entry(type = RecordType.PURPOSE, tripId = tripId, purpose = purpose))
        return true
    }

    /** 건너뛰기: the purpose stays 미분류 and only the administrator may set it. */
    @Synchronized
    fun skipPurpose(tripId: String): Boolean {
        val r = row(tripId) ?: return false
        if (r.purpose != null || r.purposeSkipped || r.unverified) return false
        append(Entry(type = RecordType.PURPOSE, tripId = tripId, purpose = null, note = Logbook.NOTE_SKIP))
        return true
    }

    /** The administrator's change (the screen checked their number); signed with their name. */
    @Synchronized
    fun classifyByAdmin(tripId: String, purpose: Purpose, admin: Driver): Boolean {
        val r = row(tripId) ?: return false
        if (r.kind != io.tripsign.app.core.ViewKind.TRIP || r.purpose == purpose) return r.purpose == purpose
        // 0.5.2a (안 B): a 미인증 운행 gets a purpose only after a phone check confirmed who was there
        if (r.unverified && !r.authConfirmed) return false
        append(Entry(type = RecordType.PURPOSE, tripId = tripId, purpose = purpose, note = Logbook.adminNote(admin)))
        return true
    }

    /** A phone check in front of the car confirms who was there for [tripId]: the AUTH record, then the user. */
    @Synchronized
    fun confirmTrip(tripId: String, proof: AuthProof, driver: Driver): Boolean {
        if (row(tripId) == null) return false
        append(Entry(type = RecordType.AUTH, tripId = tripId, startMs = proof.verifiedAtMs, endMs = proof.claimedAtMs, note = proof.note()))
        append(Entry(type = RecordType.ASSIGN, tripId = tripId, note = driver.encode()))
        return true
    }

    /** A phone check made for a user registration (or for a user registered before 0.5.2). */
    fun appendEnrollProof(proof: AuthProof): SealedEntry =
        append(Entry(type = RecordType.AUTH, tripId = proof.target, startMs = proof.verifiedAtMs, endMs = proof.claimedAtMs, note = proof.note()))

    /** Changes the user (form ④) of a trip. A new signed record; the earlier one stays as history. (0.5.2: UI uses [confirmTrip]) */
    fun assignDriver(tripId: String, driver: Driver): Boolean {
        val exists = _entries.value.any { it.entry.type == RecordType.TRIP && it.entry.tripId == tripId }
        if (!exists) return false
        val current = Logbook.rows(_entries.value).firstOrNull { it.id == tripId }?.driver
        if (current == driver) return true
        append(Entry(type = RecordType.ASSIGN, tripId = tripId, note = driver.encode()))
        return true
    }

    /** Saves form ① 차종 / ② 자동차등록번호 as a signed record (only when it changed). */
    fun setVehicle(v: Vehicle) {
        val clean = Vehicle.decode(v.encode()) ?: return
        if (Logbook.latestVehicle(_entries.value) == clean) return
        append(Entry(type = RecordType.VEHICLE, tripId = "-", note = clean.encode()))
    }

    /** Last odometer value the ledger has accounted for (continuity reference). */
    fun lastOdometerKm(): Double? = _entries.value.lastOrNull {
        it.entry.type != RecordType.PURPOSE && it.entry.endOdoKm != null
    }?.entry?.endOdoKm

    private fun toJson(s: SealedEntry): JSONObject {
        val e = s.entry
        return JSONObject().apply {
            put("seq", s.seq)
            put("createdAtMs", s.createdAtMs)
            put("type", e.type.name)
            put("tripId", e.tripId)
            put("startMs", e.startMs)
            put("endMs", e.endMs)
            put("startOdoKm", e.startOdoKm ?: JSONObject.NULL)
            put("endOdoKm", e.endOdoKm ?: JSONObject.NULL)
            put("speedKm", e.speedKm)
            put("distanceKm", e.distanceKm)
            put("distanceSource", e.distanceSource.name)
            put("maxSpeedKmh", e.maxSpeedKmh)
            put("purpose", e.purpose?.name ?: JSONObject.NULL)
            put("note", e.note)
            put("prevHash", s.prevHash)
            put("hash", s.hash)
            put("signature", s.signature)
        }
    }

    private fun JSONObject.optDoubleOrNull(name: String): Double? = if (isNull(name)) null else getDouble(name)

    private fun fromJson(o: JSONObject): SealedEntry {
        val entry = Entry(
            type = RecordType.valueOf(o.getString("type")),
            tripId = o.getString("tripId"),
            startMs = o.getLong("startMs"),
            endMs = o.getLong("endMs"),
            startOdoKm = o.optDoubleOrNull("startOdoKm"),
            endOdoKm = o.optDoubleOrNull("endOdoKm"),
            speedKm = o.getDouble("speedKm"),
            distanceKm = o.getDouble("distanceKm"),
            distanceSource = DistanceSource.valueOf(o.getString("distanceSource")),
            maxSpeedKmh = o.getDouble("maxSpeedKmh"),
            purpose = if (o.isNull("purpose")) null else Purpose.valueOf(o.getString("purpose")),
            note = o.optString("note", "")
        )
        return SealedEntry(
            seq = o.getLong("seq"),
            createdAtMs = o.getLong("createdAtMs"),
            entry = entry,
            prevHash = o.getString("prevHash"),
            hash = o.getString("hash"),
            signature = o.getString("signature")
        )
    }
}
