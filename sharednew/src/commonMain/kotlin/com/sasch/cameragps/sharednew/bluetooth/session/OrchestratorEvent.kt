package com.sasch.cameragps.sharednew.bluetooth.session

/**
 * One-shot events for platform shells to map to side effects
 * (sounds, notifications, disconnects). Continuous state (phases,
 * remote feature) lives in [CameraSessionRegistry.sessions] instead.
 */
sealed interface OrchestratorEvent {
    data class DeviceConnected(val identifier: String) : OrchestratorEvent
    data class DeviceDisconnected(val identifier: String) : OrchestratorEvent
    data class HandshakeCompleted(val identifier: String) : OrchestratorEvent

    /** Pairing retries exhausted — the shell should cancel the connection. */
    data class PairingFailed(val identifier: String) : OrchestratorEvent

    data object FirstLocationAcquired : OrchestratorEvent
    data object LocationUnavailable : OrchestratorEvent
}
