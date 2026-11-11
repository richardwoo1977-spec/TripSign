// ver 1.3.0 | 2026-11-07 13:25
package io.tripsign.app.core

enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

enum class Purpose(val label: String, val isBusiness: Boolean) {
    BUSINESS("업무", true),
    COMMUTE("출퇴근", true),
    PERSONAL("개인", false)
}

enum class RecordType { TRIP, GAP, ANOMALY, PURPOSE, ASSIGN, VEHICLE, WARN, AUTH, DENY }

enum class DistanceSource { ODOMETER, SPEED, NONE }

/**
 * One ledger entry before it is sealed into the hash chain.
 * TRIP    : a detected drive (P -> moving -> P confirmed)
 * GAP     : odometer advanced while TripSign was not recording (미기록 구간)
 * ANOMALY : odometer went backwards or other impossible reading
 * PURPOSE : purpose chosen for a TRIP; the latest PURPOSE for a trip wins, earlier ones stay as history
 * ASSIGN  : user (form ④ 부서·성명) of a TRIP, note = "부서\u001F성명"; latest wins, earlier ones stay as history
 * VEHICLE : vehicle facts (form ① 차종, ② 자동차등록번호), tripId = "-", note = "차종\u001F등록번호"; latest wins
 *
 * 0.5.2 additions (older readers skip types they do not know):
 * TRIP.note    : "unverified" = 미인증 운행 — the car left with nobody logged in (since 0.5.2f, whether or not a warning
 *                reached the driver; the WARN records before the trip tell how the warning was delivered)
 * PURPOSE.note : "skip" (purpose = null) = 건너뜀, the purpose stays 미분류 and only the administrator may set it;
 *                "admin\u001F부서\u001F성명" = set by that administrator after the driver's window closed
 * WARN    : the "who is driving?" warning reached the driver; tripId = "W<ms>",
 *           note = "screen" (shown) | "notice" (notification posted) | "voice" (spoken, since 0.5.2f)
 * AUTH    : a server-signed identity check made in front of this car (QR → phone → e-mail code → code typed in the car);
 *           tripId = the trip it confirms, or "U<ms>" for a user registration; note = "<assertion>\u001F<signature>"
 *
 * 0.5.2g addition:
 * DENY    : at the next car start someone pressed "내가 아니다" on the 미인증 운행 card; tripId = that trip,
 *           startMs = when, note = "notme". Pressed before logging in, so it does not say who pressed it.
 */
data class Entry(
    val type: RecordType,
    val tripId: String,
    val startMs: Long = 0L,
    val endMs: Long = 0L,
    val startOdoKm: Double? = null,
    val endOdoKm: Double? = null,
    val speedKm: Double = 0.0,
    val distanceKm: Double = 0.0,
    val distanceSource: DistanceSource = DistanceSource.NONE,
    val maxSpeedKmh: Double = 0.0,
    val purpose: Purpose? = null,
    val note: String = ""
)

data class SealedEntry(
    val seq: Long,
    val createdAtMs: Long,
    val entry: Entry,
    val prevHash: String,
    val hash: String,
    val signature: String
)
