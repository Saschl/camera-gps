package com.sasch.cameragps.sharednew

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.app_disabled_message
import cameragps.sharednew.generated.resources.app_disabled_title
import cameragps.sharednew.generated.resources.app_settings
import cameragps.sharednew.generated.resources.further_help
import cameragps.sharednew.generated.resources.ios_accessory_migration_action
import cameragps.sharednew.generated.resources.ios_accessory_migration_message
import cameragps.sharednew.generated.resources.ios_accessory_migration_restart
import cameragps.sharednew.generated.resources.ios_accessory_migration_title
import cameragps.sharednew.generated.resources.ios_add_camera
import cameragps.sharednew.generated.resources.ios_no_cameras_message
import cameragps.sharednew.generated.resources.ios_no_cameras_title
import cameragps.sharednew.generated.resources.ios_troubleshooting_got_it
import cameragps.sharednew.generated.resources.ios_troubleshooting_need_help
import cameragps.sharednew.generated.resources.ios_troubleshooting_step_1_bluetooth
import cameragps.sharednew.generated.resources.ios_troubleshooting_step_2_pairing_mode
import cameragps.sharednew.generated.resources.ios_troubleshooting_step_3_location_linking
import cameragps.sharednew.generated.resources.ios_troubleshooting_step_4_location_permission
import cameragps.sharednew.generated.resources.ios_troubleshooting_step_5_creators_app
import cameragps.sharednew.generated.resources.ios_troubleshooting_step_6_remote_control
import cameragps.sharednew.generated.resources.ios_troubleshooting_title
import com.sasch.cameragps.sharednew.bluetooth.BluetoothDeviceInfo
import com.sasch.cameragps.sharednew.bluetooth.accessory.PendingMigration
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
    hapticsEnabled: Boolean,
    /** Saved cameras that still need confirming in the iOS setup sheet. */
    migrationCandidates: List<PendingMigration>,
    /** True when the remaining cameras can only be confirmed after a restart. */
    migrationNeedsRestart: Boolean,
    onMigrate: () -> Unit,
    onAddCamera: () -> Unit,
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

            // Saved-but-unconfirmed cameras still populate the list from the
            // database, so this branch must NOT be gated on the list being
            // empty: doing that hid the prompt from exactly the users who had
            // cameras to confirm, leaving rows that could never connect.
            migrationCandidates.isNotEmpty() && devices.isEmpty() -> {
                MigrationCard(
                    needsRestart = migrationNeedsRestart,
                    onMigrate = onMigrate,
                )
            }

            devices.isEmpty() -> {
                EmptyStateCard(
                    title = stringResource(Res.string.ios_no_cameras_title),
                    message = stringResource(Res.string.ios_no_cameras_message),
                    actionLabel = stringResource(Res.string.ios_add_camera),
                    onAction = onAddCamera,
                )
            }

            else -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Above the list rather than instead of it: a camera that is
                    // already confirmed has to stay usable while another one is
                    // still waiting.
                    if (migrationCandidates.isNotEmpty()) {
                        MigrationCard(
                            needsRestart = migrationNeedsRestart,
                            onMigrate = onMigrate,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                    SharedDeviceList(
                        devices = devices,
                        items = items,
                        hapticsEnabled = hapticsEnabled,
                        // Bottom padding keeps the last card above the overlay button
                        contentPadding = PaddingValues(top = 16.dp, bottom = 84.dp),
                        onConnect = onConnect,
                        onTriggerRemoteShutter = onTriggerRemoteShutter,
                        onDelete = onDelete,
                        onOpenDetails = onOpenDetails,
                    )
                }
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

/**
 * Prompt to confirm cameras saved before the AccessorySetupKit switch.
 *
 * Rendered both as the whole screen (nothing else to show) and as a banner above
 * an existing list, because an unconfirmed camera still appears in the list from
 * the database and would otherwise look like an ordinary device that simply
 * refuses to connect.
 */
@Composable
private fun MigrationCard(
    needsRestart: Boolean,
    onMigrate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(Res.string.ios_accessory_migration_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (needsRestart) {
                    stringResource(Res.string.ios_accessory_migration_restart)
                } else {
                    stringResource(Res.string.ios_accessory_migration_message)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Always offered, including after a failure: hiding it left the
            // user with advice and no way to act on it, and a retry usually
            // succeeds once the previous central has actually gone away.
            TextButton(onClick = onMigrate) {
                Text(stringResource(Res.string.ios_accessory_migration_action))
            }
        }
    }
}
