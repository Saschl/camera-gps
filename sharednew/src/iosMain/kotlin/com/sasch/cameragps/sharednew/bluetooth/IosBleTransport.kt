package com.sasch.cameragps.sharednew.bluetooth

import com.diamondedge.logging.logging
import com.sasch.cameragps.sharednew.bluetooth.session.PairingRetryPolicy
import com.sasch.cameragps.sharednew.bluetooth.transport.BleOperationStatus
import com.sasch.cameragps.sharednew.bluetooth.transport.BlePeripheralTransport
import com.sasch.cameragps.sharednew.bluetooth.transport.BleTransportEvent
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicPropertyIndicate
import platform.CoreBluetooth.CBCharacteristicPropertyNotify
import platform.CoreBluetooth.CBCharacteristicWriteWithResponse
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBPeripheralStateConnected
import platform.CoreBluetooth.CBService
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.create
import platform.darwin.NSObject
import platform.posix.memcpy

/**
 * iOS implementation of [BlePeripheralTransport] over CoreBluetooth.
 *
 * Owns the peripheral delegate, the per-peripheral characteristic cache and
 * the two-phase (services → characteristics) discovery including the pairing
 * gate: subscribing to a notifiable characteristic forces iOS to pair before
 * the handshake starts. Everything above (sequencing, retries, session state)
 * lives in the shared orchestrator.
 *
 * [IosBluetoothController] owns the central manager and calls
 * [attachPeripheral]/[detachPeripheral] from its central delegate.
 *
 * Main-thread only (CoreBluetooth contract; the central uses the main queue).
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosBleTransport(
    private val scope: CoroutineScope,
    private val pairingPolicy: PairingRetryPolicy = PairingRetryPolicy(),
    /** Pairing gate retries exhausted — the controller should cancel the connection. */
    private val onPairingGateExhausted: (CBPeripheral) -> Unit,
) : BlePeripheralTransport {

    private val log = logging()

    private val eventChannel = Channel<BleTransportEvent>(Channel.UNLIMITED)
    override val events: Flow<BleTransportEvent> = eventChannel.receiveAsFlow()

    private class DiscoveryState {
        var outstandingServices = 0
        var gating = false
        var gateRetryCount = 0
        var gateCharacteristic: CBCharacteristic? = null
    }

    private class PeripheralHandle(val peripheral: CBPeripheral) {
        /** Shared UUID string (lowercase) → discovered characteristic. */
        val characteristicsByUuid = mutableMapOf<String, CBCharacteristic>()
        val notifiableCharacteristics = mutableListOf<CBCharacteristic>()
        var discovery: DiscoveryState? = null
        var connectedAnnounced = false
    }

    /** Uppercased peripheral UUID string → handle. */
    private val handles = mutableMapOf<String, PeripheralHandle>()

    // ---------------------------------------------------------------------------
    // API for IosBluetoothController's central delegate
    // ---------------------------------------------------------------------------

    /**
     * Register a connected peripheral (fresh connect or state restoration) and
     * announce it to the orchestrator. Idempotent.
     */
    fun attachPeripheral(peripheral: CBPeripheral) {
        val handle = handleFor(peripheral)
        if (!handle.connectedAnnounced) {
            handle.connectedAnnounced = true
            eventChannel.trySend(BleTransportEvent.Connected(identifierOf(peripheral)))
        }
    }

    /** Register a known-but-not-yet-connected peripheral (restore path) so delegate callbacks are not lost. */
    fun registerPeripheral(peripheral: CBPeripheral) {
        handleFor(peripheral)
    }

    private fun handleFor(peripheral: CBPeripheral): PeripheralHandle {
        val id = identifierOf(peripheral)
        peripheral.delegate = peripheralDelegate
        return handles.getOrPut(id) { PeripheralHandle(peripheral) }
    }

    /** Drop a peripheral and announce the disconnect to the orchestrator. */
    fun detachPeripheral(identifier: String) {
        val id = identifier.uppercase()
        if (handles.remove(id) != null) {
            eventChannel.trySend(BleTransportEvent.Disconnected(id, statusCode = null))
        }
    }

    /** Silently drop all peripherals (force shutdown — the caller already tore state down). */
    fun detachAll() {
        handles.clear()
    }

    // ---------------------------------------------------------------------------
    // BlePeripheralTransport
    // ---------------------------------------------------------------------------

    override fun isConnected(identifier: String): Boolean =
        handles[identifier.uppercase()]?.peripheral?.state == CBPeripheralStateConnected

    override fun hasCharacteristic(identifier: String, characteristicUuid: String): Boolean =
        handles[identifier.uppercase()]
            ?.characteristicsByUuid?.containsKey(characteristicUuid.lowercase()) == true

    override fun initiateWrite(
        identifier: String,
        characteristicUuid: String,
        value: ByteArray,
    ): Boolean {
        val handle = handles[identifier.uppercase()] ?: return false
        val characteristic =
            handle.characteristicsByUuid[characteristicUuid.lowercase()] ?: return false
        handle.peripheral.writeValue(
            data = value.toNSData(),
            forCharacteristic = characteristic,
            type = CBCharacteristicWriteWithResponse,
        )
        return true
    }

    override fun initiateRead(identifier: String, characteristicUuid: String): Boolean {
        val handle = handles[identifier.uppercase()] ?: return false
        val characteristic =
            handle.characteristicsByUuid[characteristicUuid.lowercase()] ?: return false
        handle.peripheral.readValueForCharacteristic(characteristic)
        return true
    }

    override fun initiateSubscribe(
        identifier: String,
        characteristicUuid: String,
        enable: Boolean,
    ): Boolean {
        val id = identifier.uppercase()
        val handle = handles[id] ?: return false
        val characteristic =
            handle.characteristicsByUuid[characteristicUuid.lowercase()] ?: return false
        if (characteristic.isNotifying == enable) {
            // Already in the requested state — complete the queued operation right away
            eventChannel.trySend(
                BleTransportEvent.SubscriptionChanged(
                    id, sharedUuidFor(characteristic), enable, BleOperationStatus.Success,
                )
            )
            return true
        }
        handle.peripheral.setNotifyValue(enable, forCharacteristic = characteristic)
        return true
    }

    override fun initiateDiscoverServices(identifier: String): Boolean {
        val handle = handles[identifier.uppercase()] ?: return false
        handle.characteristicsByUuid.clear()
        handle.notifiableCharacteristics.clear()
        handle.discovery = DiscoveryState()
        handle.peripheral.discoverServices(
            listOf(
                LOCATION_SERVICE_UUID,
                CONTROL_SERVICE_UUID,
                REMOTE_SERVICE_UUID,
            )
        )
        return true
    }

    // ---------------------------------------------------------------------------
    // Peripheral delegate
    // ---------------------------------------------------------------------------

    private val peripheralDelegate = object : NSObject(), CBPeripheralDelegateProtocol {

        @ObjCSignatureOverride
        override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) {
            val id = identifierOf(peripheral)
            val handle = handles[id] ?: return
            val discovery = handle.discovery ?: return

            val services = peripheral.services.orEmpty()
            if (didDiscoverServices != null || services.isEmpty()) {
                log.e { "Service discovery failed for $id: ${didDiscoverServices?.localizedDescription ?: "no services"}" }
                handle.discovery = null
                eventChannel.trySend(BleTransportEvent.ServicesDiscovered(id, success = false))
                return
            }

            discovery.outstandingServices = services.size
            services.forEach { service ->
                peripheral.discoverCharacteristics(
                    characteristicUUIDs = null,
                    forService = service as CBService,
                )
            }
        }

        @ObjCSignatureOverride
        override fun peripheral(
            peripheral: CBPeripheral,
            didDiscoverCharacteristicsForService: CBService,
            error: NSError?,
        ) {
            val id = identifierOf(peripheral)
            val handle = handles[id] ?: return
            val discovery = handle.discovery ?: return

            didDiscoverCharacteristicsForService.characteristics?.forEach { characteristicAny ->
                val characteristic = characteristicAny as CBCharacteristic
                log.v {
                    "  Discovered characteristic ${characteristic.UUID}  props=0x${
                        characteristic.properties.toString(16)
                    }"
                }

                knownCharacteristicUuids.firstOrNull { it.second == characteristic.UUID }
                    ?.let { (sharedUuid, _) ->
                        handle.characteristicsByUuid[sharedUuid.lowercase()] = characteristic
                    }

                val supportsNotify =
                    (characteristic.properties and CBCharacteristicPropertyNotify) != 0uL ||
                        (characteristic.properties and CBCharacteristicPropertyIndicate) != 0uL
                if (supportsNotify) {
                    handle.notifiableCharacteristics.add(characteristic)
                }
            }

            discovery.outstandingServices--
            if (discovery.outstandingServices <= 0) {
                startPairingGate(id, handle, discovery)
            }
        }

        @ObjCSignatureOverride
        override fun peripheral(
            peripheral: CBPeripheral,
            didUpdateValueForCharacteristic: CBCharacteristic,
            error: NSError?,
        ) {
            val id = identifierOf(peripheral)
            if (handles[id] == null) return
            val sharedUuid = sharedUuidFor(didUpdateValueForCharacteristic)
            log.v { "Value update for $sharedUuid (error=${error?.code} / ${error?.localizedDescription})" }

            // This callback serves both read responses and notifications. The config
            // read is the protocol's only read; everything else is camera-initiated.
            val isReadResponse = sharedUuid.equals(
                SonyBluetoothConstants.CHARACTERISTIC_READ_UUID,
                ignoreCase = true,
            )
            if (isReadResponse) {
                eventChannel.trySend(
                    BleTransportEvent.CharacteristicRead(
                        id,
                        sharedUuid,
                        didUpdateValueForCharacteristic.value?.toByteArray() ?: ByteArray(0),
                        statusOf(error),
                    )
                )
                return
            }

            if (error != null) {
                log.e { "Notification error for $sharedUuid: ${error.localizedDescription}" }
                return
            }
            val value = didUpdateValueForCharacteristic.value?.toByteArray() ?: return
            eventChannel.trySend(BleTransportEvent.CharacteristicChanged(id, sharedUuid, value))
        }

        @ObjCSignatureOverride
        override fun peripheral(
            peripheral: CBPeripheral,
            didWriteValueForCharacteristic: CBCharacteristic,
            error: NSError?,
        ) {
            val id = identifierOf(peripheral)
            if (handles[id] == null) return
            val sharedUuid = sharedUuidFor(didWriteValueForCharacteristic)
            log.v { "Write for $sharedUuid completed (error=${error?.code} / ${error?.localizedDescription})" }

            if (error != null && !isAuthenticationError(error)) {
                log.e { "BLE write failed for $sharedUuid: ${error.localizedDescription}" }
            }
            eventChannel.trySend(
                BleTransportEvent.CharacteristicWritten(id, sharedUuid, statusOf(error))
            )
        }

        @ObjCSignatureOverride
        override fun peripheral(
            peripheral: CBPeripheral,
            didUpdateNotificationStateForCharacteristic: CBCharacteristic,
            error: NSError?,
        ) {
            val id = identifierOf(peripheral)
            val handle = handles[id] ?: return
            val discovery = handle.discovery

            if (discovery?.gating == true) {
                handlePairingGateResult(
                    id, handle, discovery,
                    didUpdateNotificationStateForCharacteristic, error,
                )
                return
            }

            eventChannel.trySend(
                BleTransportEvent.SubscriptionChanged(
                    id,
                    sharedUuidFor(didUpdateNotificationStateForCharacteristic),
                    didUpdateNotificationStateForCharacteristic.isNotifying,
                    statusOf(error),
                )
            )
        }
    }

    // ---------------------------------------------------------------------------
    // Pairing gate
    // ---------------------------------------------------------------------------

    /**
     * Subscribing to a notifiable characteristic forces iOS to pair with the
     * camera before the handshake. Auth errors are retried per [pairingPolicy];
     * on success the gate subscription is removed again.
     */
    private fun startPairingGate(id: String, handle: PeripheralHandle, discovery: DiscoveryState) {
        val target = handle.notifiableCharacteristics.firstOrNull()
        if (target == null) {
            log.d { "No notifiable characteristic – proceeding without explicit pairing" }
            handle.discovery = null
            eventChannel.trySend(BleTransportEvent.ServicesDiscovered(id, success = true))
            return
        }
        discovery.gating = true
        discovery.gateCharacteristic = target
        log.d { "Subscribing to notifications on ${target.UUID.UUIDString} to trigger pairing" }
        handle.peripheral.setNotifyValue(true, forCharacteristic = target)
    }

    private fun handlePairingGateResult(
        id: String,
        handle: PeripheralHandle,
        discovery: DiscoveryState,
        characteristic: CBCharacteristic,
        error: NSError?,
    ) {
        if (isAuthenticationError(error)) {
            discovery.gateRetryCount++
            if (discovery.gateRetryCount > pairingPolicy.maxRetries) {
                log.e { "Pairing failed after ${pairingPolicy.maxRetries} retries, disconnecting" }
                handle.discovery = null
                onPairingGateExhausted(handle.peripheral)
                eventChannel.trySend(BleTransportEvent.ServicesDiscovered(id, success = false))
                return
            }
            log.d {
                "Auth error – retrying pairing in ${pairingPolicy.retryDelayMs}ms " +
                    "(attempt ${discovery.gateRetryCount}/${pairingPolicy.maxRetries})"
            }
            scope.launch {
                delay(pairingPolicy.retryDelayMs)
                if (handle.discovery === discovery) {
                    handle.peripheral.setNotifyValue(true, forCharacteristic = characteristic)
                }
            }
            return
        }

        if (error != null) {
            log.d { "Pairing gate subscription failed (non-auth): ${error.localizedDescription} – continuing" }
        } else {
            log.d { "Pairing gate subscription succeeded – device is paired" }
            handle.peripheral.setNotifyValue(false, forCharacteristic = characteristic)
        }
        handle.discovery = null
        eventChannel.trySend(BleTransportEvent.ServicesDiscovered(id, success = true))
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun identifierOf(peripheral: CBPeripheral): String =
        peripheral.identifier.UUIDString.uppercase()

    private fun statusOf(error: NSError?): BleOperationStatus = when {
        error == null -> BleOperationStatus.Success
        isAuthenticationError(error) -> BleOperationStatus.AuthError
        else -> BleOperationStatus.Failure
    }

    private fun isAuthenticationError(error: NSError?): Boolean {
        if (error == null) return false
        return error.code == SonyBluetoothConstants.ATT_ERROR_INSUFFICIENT_AUTHENTICATION.toLong() ||
            error.code == SonyBluetoothConstants.ATT_ERROR_INSUFFICIENT_ENCRYPTION.toLong()
    }

    /** Map a CoreBluetooth characteristic back to the shared UUID string. */
    private fun sharedUuidFor(characteristic: CBCharacteristic): String =
        knownCharacteristicUuids.firstOrNull { it.second == characteristic.UUID }?.first
            ?: characteristic.UUID.UUIDString

    private companion object {
        val LOCATION_SERVICE_UUID = CBUUID.UUIDWithString(SonyBluetoothConstants.SERVICE_UUID)
        val CONTROL_SERVICE_UUID = CBUUID.UUIDWithString(SonyBluetoothConstants.CONTROL_SERVICE_UUID)
        val REMOTE_SERVICE_UUID = CBUUID.UUIDWithString(SonyBluetoothConstants.REMOTE_SERVICE_UUID)

        /** Shared UUID string ↔ CBUUID pairs, built once (CBUUID canonicalizes to short form). */
        val knownCharacteristicUuids: List<Pair<String, CBUUID>> = listOf(
            SonyBluetoothConstants.CHARACTERISTIC_UUID,
            SonyBluetoothConstants.CHARACTERISTIC_READ_UUID,
            SonyBluetoothConstants.CHARACTERISTIC_ENABLE_UNLOCK_GPS_COMMAND,
            SonyBluetoothConstants.CHARACTERISTIC_ENABLE_LOCK_GPS_COMMAND,
            SonyBluetoothConstants.CHARACTERISTIC_LOCATION_ENABLED_IN_CAMERA,
            SonyBluetoothConstants.TIME_SYNC_CHARACTERISTIC_UUID,
            SonyBluetoothConstants.REMOTE_CHARACTERISTIC_UUID,
            SonyBluetoothConstants.REMOTE_STATUS_UUID,
        ).map { it to CBUUID.UUIDWithString(it) }
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun ByteArray.toNSData(): NSData = usePinned {
    NSData.create(bytes = it.addressOf(0), length = size.toULong())
}

@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)

    return ByteArray(size).apply {
        usePinned { pinned ->
            memcpy(pinned.addressOf(0), bytes, length)
        }
    }
}
