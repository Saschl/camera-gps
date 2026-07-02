package com.sasch.cameragps.sharednew.bluetooth.session

import com.sasch.cameragps.sharednew.bluetooth.BleSessionPhase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * State of one camera session. Replaces the Android `CameraConnectionConfig`
 * state fields and the iOS `PeripheralSession`/`PeripheralPhase` pair.
 */
data class CameraSession(
    /** Uppercased MAC address (Android) / peripheral UUID string (iOS). */
    val identifier: String,
    val phase: BleSessionPhase = BleSessionPhase.Connecting,
    val remoteFeatureActive: Boolean = false,
    /** Consecutive auth-error retries (iOS pairing); reset on any success. */
    val pairingRetryCount: Int = 0,
    /** One-shot retry guard for the config read (intermittent GATT 133 on Android). */
    val hasRetriedConfigRead: Boolean = false,
)

/**
 * Per-device session registry, exposed as a StateFlow for both platform UIs.
 * All mutations happen on the orchestrator's confined dispatcher.
 */
class CameraSessionRegistry {

    private val _sessions = MutableStateFlow<Map<String, CameraSession>>(emptyMap())
    val sessions: StateFlow<Map<String, CameraSession>> = _sessions

    /** Create-or-update. Only connection setup should create sessions. */
    fun upsert(identifier: String, transform: (CameraSession) -> CameraSession = { it }) {
        val id = identifier.uppercase()
        val current = _sessions.value[id] ?: CameraSession(id)
        _sessions.value = _sessions.value + (id to transform(current))
    }

    /** Update only if a session exists — phase/remote updates must not resurrect removed sessions. */
    fun updateIfPresent(identifier: String, transform: (CameraSession) -> CameraSession) {
        val id = identifier.uppercase()
        val existing = _sessions.value[id] ?: return
        _sessions.value = _sessions.value + (id to transform(existing))
    }

    fun remove(identifier: String) {
        _sessions.value = _sessions.value - identifier.uppercase()
    }

    fun clear() {
        _sessions.value = emptyMap()
    }

    fun get(identifier: String): CameraSession? = _sessions.value[identifier.uppercase()]

    /** Devices whose handshake completed and are receiving location packets. */
    fun readyIdentifiers(): Set<String> =
        _sessions.value.filterValues { it.phase == BleSessionPhase.Transmitting }.keys

    /** Number of devices with a live connection (any phase past connecting, minus errors). */
    fun activeCount(): Int = _sessions.value.values.count { it.phase.isActiveConnection() }

    private fun BleSessionPhase.isActiveConnection(): Boolean = when (this) {
        BleSessionPhase.Disconnected,
        BleSessionPhase.Connecting,
        BleSessionPhase.Error,
            -> false

        else -> true
    }
}
