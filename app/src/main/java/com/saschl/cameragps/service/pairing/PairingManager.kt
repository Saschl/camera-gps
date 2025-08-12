/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.saschl.cameragps.service.pairing

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.companion.CompanionDeviceManager
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.getSystemService
import com.saschl.cameragps.service.AssociatedDeviceCompat
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * A centralized pairing manager that handles the complete pairing flow for devices.
 * This component manages pairing dialogs, state transitions, and device presence observation.
 */
@Composable
fun PairingManager(
    device: AssociatedDeviceCompat,
    deviceManager: CompanionDeviceManager,
    onPairingComplete: () -> Unit = {},
    onPairingCancelled: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var pairingDialogState by remember(device) {
        mutableStateOf(PairingDialogState.Hidden)
    }

    // Check if pairing is needed when device changes
    LaunchedEffect(device) {
        scope.launch {
            val bluetoothManager = context.getSystemService<BluetoothManager>()
            val adapter = bluetoothManager?.adapter
            val bluetoothDevice = device.device ?: adapter?.getRemoteDevice(device.address)

            if (bluetoothDevice != null && !isDevicePaired(adapter, device.address)) {
                Timber.i("Device ${device.name} not paired, showing pairing confirmation dialog")
                pairingDialogState = PairingDialogState(device = device, isVisible = true)
            } else if (isDevicePaired(adapter, device.address)) {
                Timber.i("Device ${device.name} is already paired")
                startDevicePresenceObservation(deviceManager, device)
                onPairingComplete()
            }
        }
    }

    // Show pairing dialog when needed
    if (pairingDialogState.isVisible && pairingDialogState.device != null) {
        PairingConfirmationDialogWithLoading(
            deviceName = pairingDialogState.device!!.name,
            isPairing = pairingDialogState.isPairing,
            pairingResult = pairingDialogState.pairingResult,
            onConfirm = {
                handlePairingConfirmation(
                    context = context,
                    device = device,
                    currentState = pairingDialogState,
                    onStateUpdate = { pairingDialogState = it }
                )
            },
            onDismiss = {
                Timber.i("User cancelled pairing for ${device.name}")
                pairingDialogState = PairingDialogState.Hidden
                onPairingCancelled()
            },
            onRetry = {
                pairingDialogState = pairingDialogState.copy(
                    isPairing = false,
                    pairingResult = null
                )
            }
        )
    }

    // Listen for bonding state changes
    if (pairingDialogState.isPairing && pairingDialogState.device != null) {
        val bluetoothManager = context.getSystemService<BluetoothManager>()
        val adapter = bluetoothManager?.adapter
        val bluetoothDevice = pairingDialogState.device!!.device ?: adapter?.getRemoteDevice(pairingDialogState.device!!.address)

        if (bluetoothDevice != null) {
            BondingStateListener(
                device = bluetoothDevice,
                onBondingSuccess = {
                    Timber.i("Pairing successful for ${device.name}")
                    pairingDialogState = pairingDialogState.copy(
                        isPairing = false,
                        pairingResult = PairingResult.SUCCESS
                    )

                    // Start observing device presence after successful pairing
                    scope.launch {
                        startDevicePresenceObservation(deviceManager, device)
                        onPairingComplete()
                    }
                },
                onBondingFailed = {
                    Timber.w("Pairing failed for ${device.name}")
                    pairingDialogState = pairingDialogState.copy(
                        isPairing = false,
                        pairingResult = PairingResult.FAILED
                    )
                }
            )
        }
    }
}

/**
 * Handles the pairing confirmation logic
 */
private fun handlePairingConfirmation(
    context: Context,
    device: AssociatedDeviceCompat,
    currentState: PairingDialogState,
    onStateUpdate: (PairingDialogState) -> Unit
) {
    onStateUpdate(currentState.copy(isPairing = true))

    val bluetoothManager = context.getSystemService<BluetoothManager>()
    val adapter = bluetoothManager?.adapter
    val bluetoothDevice = device.device ?: adapter?.getRemoteDevice(device.address)

    if (bluetoothDevice != null) {
        Timber.i("User confirmed pairing for ${device.name}, initiating pairing")
        val pairingResult = initiateBluetoothPairing(bluetoothDevice)
        if (!pairingResult) {
            Timber.w("Failed to initiate pairing for ${device.name}")
            onStateUpdate(
                currentState.copy(
                    isPairing = false,
                    pairingResult = PairingResult.FAILED
                )
            )
        }
    } else {
        onStateUpdate(
            currentState.copy(
                isPairing = false,
                pairingResult = PairingResult.FAILED
            )
        )
    }
}

/**
 * Starts device presence observation after successful pairing
 */
fun startDevicePresenceObservation(
    deviceManager: CompanionDeviceManager,
    device: AssociatedDeviceCompat
) {
    Timber.i("Starting device presence observation for ${device.name}")
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
        deviceManager.startObservingDevicePresence(
            ObservingDevicePresenceRequest.Builder().setAssociationId(device.id).build()
        )
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        deviceManager.startObservingDevicePresence(device.address)
    }
}

/**
 * A simplified pairing trigger that just shows the pairing dialog for a device
 */
@Composable
fun PairingTrigger(
    device: BluetoothDevice,
    onPairingStateChange: (BluetoothPairingState) -> Unit = {}
) {
    var pairingDialogState by remember(device) {
        mutableStateOf(PairingDialogState.Hidden)
    }

    // Track pairing state using the BluetoothPairingEffect
    BluetoothPairingEffect(
        device = device,
        onPairingStateChange = onPairingStateChange
    )

    // Show pairing dialog when needed
    if (pairingDialogState.isVisible) {
        PairingConfirmationDialogWithLoading(
            deviceName = device.name ?: device.address,
            isPairing = pairingDialogState.isPairing,
            pairingResult = pairingDialogState.pairingResult,
            onConfirm = {
                pairingDialogState = pairingDialogState.copy(isPairing = true)
                val success = initiateBluetoothPairing(device)
                if (!success) {
                    pairingDialogState = pairingDialogState.copy(
                        isPairing = false,
                        pairingResult = PairingResult.FAILED
                    )
                }
            },
            onDismiss = {
                pairingDialogState = PairingDialogState.Hidden
            },
            onRetry = {
                pairingDialogState = pairingDialogState.copy(
                    isPairing = false,
                    pairingResult = null
                )
            }
        )
    }

    // Listen for bonding state changes
    if (pairingDialogState.isPairing) {
        BondingStateListener(
            device = device,
            onBondingSuccess = {
                pairingDialogState = pairingDialogState.copy(
                    isPairing = false,
                    pairingResult = PairingResult.SUCCESS
                )
            },
            onBondingFailed = {
                pairingDialogState = pairingDialogState.copy(
                    isPairing = false,
                    pairingResult = PairingResult.FAILED
                )
            }
        )
    }
}
