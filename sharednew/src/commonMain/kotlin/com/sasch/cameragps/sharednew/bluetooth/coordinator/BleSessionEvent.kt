package com.sasch.cameragps.sharednew.bluetooth.coordinator

import com.sasch.cameragps.sharednew.bluetooth.BleSessionPhase

/**
 * Events emitted by [BleSessionCoordinator] and applied to the session registry
 * by the orchestrator. Continuous per-device state (remote feature, shutter
 * sequence) is written to the registry directly through [BleGattPort] instead.
 */
sealed interface BleSessionEvent {
    /** BLE session phase changed for a device. */
    data class PhaseChanged(
        val identifier: String,
        val phase: BleSessionPhase,
    ) : BleSessionEvent

    /** BLE handshake finished — ready to transmit location and probe remote. */
    data class HandshakeComplete(val identifier: String) : BleSessionEvent
}
