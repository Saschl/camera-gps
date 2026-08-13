package com.sasch.cameragps.sharednew

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.app_disabled_message
import cameragps.sharednew.generated.resources.app_disabled_title
import cameragps.sharednew.generated.resources.app_settings
import cameragps.sharednew.generated.resources.further_help
import cameragps.sharednew.generated.resources.ios_no_devices_message
import cameragps.sharednew.generated.resources.ios_troubleshooting_got_it
import cameragps.sharednew.generated.resources.ios_troubleshooting_need_help
import cameragps.sharednew.generated.resources.ios_troubleshooting_step_1_bluetooth
import cameragps.sharednew.generated.resources.ios_troubleshooting_step_2_pairing_mode
import cameragps.sharednew.generated.resources.ios_troubleshooting_step_3_location_linking
import cameragps.sharednew.generated.resources.ios_troubleshooting_step_4_location_permission
import cameragps.sharednew.generated.resources.ios_troubleshooting_step_5_creators_app
import cameragps.sharednew.generated.resources.ios_troubleshooting_step_6_remote_control
import cameragps.sharednew.generated.resources.ios_troubleshooting_title
import cameragps.sharednew.generated.resources.scanning_for_cameras
import cameragps.sharednew.generated.resources.scanning_paused_message
import cameragps.sharednew.generated.resources.scanning_paused_title
import com.sasch.cameragps.sharednew.bluetooth.BluetoothDeviceInfo
import com.sasch.cameragps.sharednew.ui.devicelist.DeviceListItem
import com.sasch.cameragps.sharednew.ui.devicelist.EmptyStateCard
import com.sasch.cameragps.sharednew.ui.devicelist.SharedDeviceList
import org.jetbrains.compose.resources.stringResource

/**
 * iOS list host: owns the scanning/disabled empty states and the
 * troubleshooting overlay; the device list itself is the shared
 * [SharedDeviceList].
 */
@Composable
internal fun DeviceListContent(
    devices: List<BluetoothDeviceInfo>,
    items: Map<String, DeviceListItem>,
    isAppEnabled: Boolean,
    isScanning: Boolean,
    onOpenSettings: () -> Unit,
    onOpenHelp: () -> Unit,
    onConnect: (BluetoothDeviceInfo) -> Unit,
    onTriggerRemoteShutter: (BluetoothDeviceInfo) -> Unit,
    onDelete: (BluetoothDeviceInfo) -> Unit,
    onOpenDetails: (BluetoothDeviceInfo) -> Unit,
) {
    var showTroubleshootingDialog by remember { mutableStateOf(false) }

    if (showTroubleshootingDialog) {
        AlertDialog(
            onDismissRequest = { showTroubleshootingDialog = false },
            title = { Text(stringResource(Res.string.ios_troubleshooting_title)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(Res.string.ios_troubleshooting_step_1_bluetooth))
                    Text(stringResource(Res.string.ios_troubleshooting_step_2_pairing_mode))
                    Text(stringResource(Res.string.ios_troubleshooting_step_3_location_linking))
                    Text(stringResource(Res.string.ios_troubleshooting_step_4_location_permission))
                    Text(stringResource(Res.string.ios_troubleshooting_step_5_creators_app))
                    Text(stringResource(Res.string.ios_troubleshooting_step_6_remote_control))
                }
            },
            confirmButton = {
                TextButton(onClick = { showTroubleshootingDialog = false }) {
                    Text(stringResource(Res.string.ios_troubleshooting_got_it))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showTroubleshootingDialog = false
                    onOpenHelp()
                }) {
                    Text(stringResource(Res.string.further_help))
                }
            },
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            !isAppEnabled -> {
                EmptyStateCard(
                    title = stringResource(Res.string.app_disabled_title),
                    message = stringResource(Res.string.app_disabled_message),
                    actionLabel = stringResource(Res.string.app_settings),
                    onAction = onOpenSettings,
                )
            }

            devices.isEmpty() && !isScanning -> {
                EmptyStateCard(
                    title = stringResource(Res.string.scanning_paused_title),
                    message = stringResource(Res.string.scanning_paused_message),
                    actionLabel = stringResource(Res.string.app_settings),
                    onAction = onOpenSettings,
                )
            }

            devices.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = stringResource(Res.string.scanning_for_cameras),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(Res.string.ios_no_devices_message),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            else -> {
                SharedDeviceList(
                    devices = devices,
                    items = items,
                    // Bottom padding keeps the last card above the overlay button
                    contentPadding = PaddingValues(top = 16.dp, bottom = 84.dp),
                    onConnect = onConnect,
                    onTriggerRemoteShutter = onTriggerRemoteShutter,
                    onDelete = onDelete,
                    onOpenDetails = onOpenDetails,
                )
            }
        }

        TextButton(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            onClick = { showTroubleshootingDialog = true },
        ) {
            Text(stringResource(Res.string.ios_troubleshooting_need_help))
        }
    }
}
