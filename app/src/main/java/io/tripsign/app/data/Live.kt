// ver 1.2.0 | 2026-11-07 13:25
package io.tripsign.app.data

import io.tripsign.app.core.Gear
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class LiveState(
    val serviceRunning: Boolean = false,
    val carConnected: Boolean = false,
    val odometerConnected: Boolean = false,
    val gear: Gear = Gear.UNKNOWN,
    val speedKmh: Double = 0.0,
    val odometerKm: Double? = null,
    val driving: Boolean = false,
    val tripKm: Double = 0.0,
    val lastEvent: String = "",
    /** 0.5.2: raised by the service when the "who is driving?" warning should be on screen (ignition, trip end). */
    val warnSeq: Int = 0,
    /** The service has seen the ignition state (null = this car does not report it). */
    val ignitionOn: Boolean? = null,
    /** 0.5.2g: a phone-check screen (QR + 6 digits) is open; the repeated spoken warning waits while it is. */
    val authOpen: Boolean = false
) {
    /** Inputs are allowed only in P with no drive open; an unknown gear counts as moving. */
    val parkedIdle: Boolean get() = gear == Gear.PARK && !driving
}

/** In-process live signal state shared by the service and the UI. */
object Live {
    private val _state = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = _state

    fun update(change: (LiveState) -> LiveState) {
        _state.value = change(_state.value)
    }
}
