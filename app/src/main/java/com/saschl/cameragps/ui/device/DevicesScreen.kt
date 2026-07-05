package com.saschl.cameragps.ui.device

import android.Manifest
import android.annotation.SuppressLint
import android.companion.CompanionDeviceManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.app_name_ui
import cameragps.sharednew.generated.resources.app_settings
import cameragps.sharednew.generated.resources.help_menu_item
import cameragps.sharednew.generated.resources.permissions_missing_description
import cameragps.sharednew.generated.resources.permissions_missing_title
import cameragps.sharednew.generated.resources.settings
import cameragps.sharednew.generated.resources.view_logs
import com.sasch.cameragps.sharednew.ui.device.SharedDevicesScreen
import com.saschl.cameragps.R
import com.saschl.cameragps.service.AssociatedDeviceCompat
import com.saschl.cameragps.ui.AssociatedDevicesList
import com.saschl.cameragps.ui.getPermissionDescription
import com.saschl.cameragps.ui.pairing.PairingManager
import org.jetbrains.compose.resources.stringResource
import timber.log.Timber

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("MissingPermission")
@Composable
fun DevicesScreen(
    deviceManager: CompanionDeviceManager,
    isBluetoothEnabled: Boolean,
    isLocationEnabled: Boolean,
    associatedDevices: List<AssociatedDeviceCompat>,
    onDeviceAssociated: (AssociatedDeviceCompat) -> Unit,
    onConnect: (AssociatedDeviceCompat) -> Unit,
    onSettingsClick: () -> Unit = {},
    onHelpClick: () -> Unit = {},
    onTroubleshootingClick: () -> Unit = {},
    onLogsClick: () -> Unit = {}
) {
    val context = LocalContext.current
    // State for managing pairing after association
    var pendingPairingDevice by remember { mutableStateOf<AssociatedDeviceCompat?>(null) }

    val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.POST_NOTIFICATIONS
        )
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
    } else {
        listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH
        )
    }

    val missingPermissions = requiredPermissions.filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }

    SharedDevicesScreen(
        title = stringResource(Res.string.app_name_ui),
        topBarActions = {
            IconButton(
                onClick = onHelpClick
            ) {
                Icon(
                    painterResource(R.drawable.info_24px),
                    contentDescription = stringResource(Res.string.help_menu_item),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            IconButton(
                onClick = onLogsClick
            ) {
                Icon(
                    painterResource(R.drawable.baseline_view_list_24),
                    contentDescription = stringResource(Res.string.view_logs),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            IconButton(onClick = { onSettingsClick() }) {
                Icon(
                    painterResource(R.drawable.settings_24px),
                    contentDescription = stringResource(Res.string.settings),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    ) {

        Column(
            /* modifier = Modifier
                 .padding(innerPadding),*/
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {

            if (missingPermissions.isNotEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(Res.string.permissions_missing_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        val missingPermissionNames = missingPermissions
                            .mapNotNull { permission ->
                                getPermissionDescription(permission)?.let { res ->
                                    " - ${stringResource(res)}"
                                }
                            }
                            .joinToString("\n")
                        Text(
                            text = stringResource(
                                Res.string.permissions_missing_description,
                                missingPermissionNames
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        TextButton(
                            onClick = {
                                val intent =
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = Uri.fromParts("package", context.packageName, null)
                                    }
                                context.startActivity(intent)
                            }
                        ) {
                            Text(text = stringResource(Res.string.app_settings))
                        }
                    }
                }
            }

            ScanForDevicesMenu(
                deviceManager,
                isBluetoothEnabled,
                isLocationEnabled,
                associatedDevices,
                onSetPairingDevice = { device -> pendingPairingDevice = device },
                onDeviceAssociated = onDeviceAssociated,
                onTroubleshootingClick = onTroubleshootingClick
            )


            AssociatedDevicesList(
                associatedDevices = associatedDevices,
                onConnect = onConnect
            )

            // Handle pairing for newly associated device
            pendingPairingDevice?.let { device ->
                PairingManager(
                    device = device,
                    deviceManager = deviceManager,
                    onPairingComplete = {
                        Timber.i("Pairing completed for newly associated device ${device.name}")
                        onDeviceAssociated(device)
                        pendingPairingDevice = null
                    },
                    onPairingCancelled = {
                        Timber.i("Pairing cancelled for newly associated device ${device.name}")
                        // Still add the device even if pairing was cancelled
                        onDeviceAssociated(device)
                        pendingPairingDevice = null
                    }
                )
            }
        }
    }
}
