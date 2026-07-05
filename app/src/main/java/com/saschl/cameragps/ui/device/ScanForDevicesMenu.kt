package com.saschl.cameragps.ui.device

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.companion.CompanionDeviceManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.device_already_associated
import cameragps.sharednew.generated.resources.device_association_removed_retry
import cameragps.sharednew.generated.resources.guide_open_button
import cameragps.sharednew.generated.resources.internal_error_happened
import cameragps.sharednew.generated.resources.no_device_matching_the_given_filter_were_found
import cameragps.sharednew.generated.resources.scan_for_devices
import cameragps.sharednew.generated.resources.scan_timeout_creators_app_hint
import cameragps.sharednew.generated.resources.start
import cameragps.sharednew.generated.resources.the_request_was_canceled
import cameragps.sharednew.generated.resources.the_user_explicitly_declined_the_request
import cameragps.sharednew.generated.resources.unknown_error
import com.saschl.cameragps.service.AssociatedDeviceCompat
import com.saschl.cameragps.ui.BluetoothWarningCard
import com.saschl.cameragps.ui.LocationWarningCard
import com.saschl.cameragps.ui.pairing.isDevicePaired
import com.saschl.cameragps.utils.DeviceAssociationUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import timber.log.Timber

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
@Composable
fun ScanForDevicesMenu(
    deviceManager: CompanionDeviceManager,
    isBluetoothEnabled: Boolean,
    isLocationEnabled: Boolean,
    associatedDevices: List<AssociatedDeviceCompat>,
    onSetPairingDevice: (AssociatedDeviceCompat) -> Unit,
    onDeviceAssociated: (AssociatedDeviceCompat) -> Unit,
    onTroubleshootingClick: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var errorMessage by remember {
        mutableStateOf("")
    }
    var showTroubleshootingHint by remember {
        mutableStateOf(false)
    }
    val requestCanceledText = stringResource(Res.string.the_request_was_canceled)
    val internalErrorText = stringResource(Res.string.internal_error_happened)
    val discoveryTimeoutText =
        stringResource(Res.string.no_device_matching_the_given_filter_were_found)
    val userDeclinedText = stringResource(Res.string.the_user_explicitly_declined_the_request)
    val unknownErrorText = stringResource(Res.string.unknown_error)
    val duplicateAssociatedText = stringResource(Res.string.device_already_associated)
    val duplicateRemovedText = stringResource(Res.string.device_association_removed_retry)

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) {
        showTroubleshootingHint = it.resultCode == CompanionDeviceManager.RESULT_DISCOVERY_TIMEOUT
        when (it.resultCode) {
            CompanionDeviceManager.RESULT_OK -> {
                it.data?.let { intent ->
                    DeviceAssociationUtils.getAssociationResult(intent)?.let { device ->
                        // Device association successful, now check if pairing is needed
                        val bluetoothManager = context.getSystemService<BluetoothManager>()
                        val adapter = bluetoothManager?.adapter

                        if (associatedDevices.any { existingDevice -> existingDevice.address == device.address }) {
                            Timber.i("Device ${device.name} already associated, skipping pairing")
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                deviceManager.disassociate(device.id)
                                errorMessage = duplicateAssociatedText
                            } else {
                                @Suppress("DEPRECATION")
                                deviceManager.disassociate(device.address)
                                errorMessage = duplicateRemovedText
                            }
                            return@let
                        }
                        if (!isDevicePaired(adapter, device.address)) {
                            Timber.i("Device ${device.name} associated but not paired, initiating pairing")
                            onSetPairingDevice(device)
                        } else {
                            Timber.i("Device ${device.name} already paired, completing association")
                            onDeviceAssociated(device)
                        }
                        errorMessage = ""
                    }
                }
            }

            CompanionDeviceManager.RESULT_CANCELED -> {
                errorMessage = requestCanceledText
            }

            CompanionDeviceManager.RESULT_INTERNAL_ERROR -> {
                errorMessage = internalErrorText
            }

            CompanionDeviceManager.RESULT_DISCOVERY_TIMEOUT -> {
                errorMessage = discoveryTimeoutText
            }

            CompanionDeviceManager.RESULT_USER_REJECTED -> {
                errorMessage = userDeclinedText
            }

            else -> {
                errorMessage = unknownErrorText
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {

        if (!isBluetoothEnabled) {
            BluetoothWarningCard()
        }

        if (!isLocationEnabled) {
            LocationWarningCard()
        }

        Row {
            Text(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .weight(1f),
                text = stringResource(Res.string.scan_for_devices),
            )
            Button(
                modifier = Modifier.weight(0.5f),
                // enabled = associatedDevices.isEmpty() && isBluetoothEnabled && isLocationEnabled,
                onClick = {
                    scope.launch {
                        try {
                            val intentSender =
                                DeviceAssociationUtils.requestDeviceAssociation(deviceManager)
                            launcher.launch(IntentSenderRequest.Builder(intentSender).build())
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to start device association")
                            showTroubleshootingHint = false
                            errorMessage =
                                e.message?.takeIf { it.isNotBlank() } ?: internalErrorText
                        }
                    }
                },
            ) {
                Text(text = stringResource(Res.string.start), maxLines = 1)
            }
        }
        if (errorMessage.isNotBlank()) {
            Text(text = errorMessage, color = MaterialTheme.colorScheme.error)
            if (showTroubleshootingHint) {
                Text(
                    text = stringResource(Res.string.scan_timeout_creators_app_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onTroubleshootingClick) {
                    Text(text = stringResource(Res.string.guide_open_button))
                }
            }
        }
    }
}
