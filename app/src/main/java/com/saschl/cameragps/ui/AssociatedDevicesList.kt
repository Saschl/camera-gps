package com.saschl.cameragps.ui

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.camera_24px
import cameragps.sharednew.generated.resources.no_devices_message
import cameragps.sharednew.generated.resources.no_devices_title
import com.sasch.cameragps.sharednew.bluetooth.BluetoothDeviceInfo
import com.sasch.cameragps.sharednew.bluetooth.SonyBluetoothConstants
import com.sasch.cameragps.sharednew.ui.devicelist.DeviceListViewModel
import com.sasch.cameragps.sharednew.ui.devicelist.SharedDeviceList
import com.saschl.cameragps.AppServices
import com.saschl.cameragps.service.AssociatedDeviceCompat
import com.saschl.cameragps.service.LocationSenderService
import com.saschl.cameragps.ui.device.SCREENSHOT_MODE
import com.saschl.cameragps.ui.device.mockDeviceListItems
import com.saschl.cameragps.utils.PreferencesManager
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * Android host for the shared device list: maps CDM associations to the shared
 * identity model and wires the callbacks back to CDM/service semantics.
 */
@Composable
fun AssociatedDevicesList(
    associatedDevices: List<AssociatedDeviceCompat>,
    onConnect: (AssociatedDeviceCompat) -> Unit,
    onDisassociate: (AssociatedDeviceCompat) -> Unit,
) {
    val context = LocalContext.current
    val viewModel: DeviceListViewModel = viewModel {
        DeviceListViewModel(AndroidDeviceListDataSource(AppServices.from(context)))
    }
    val realItems by viewModel.items.collectAsState()
    val items = if (SCREENSHOT_MODE) mockDeviceListItems else realItems

    if (associatedDevices.isEmpty()) {
        EmptyDevicesCard()
        return
    }

    val devices = associatedDevices.map { compat ->
        BluetoothDeviceInfo(
            identifier = compat.address.uppercase(),
            name = compat.name,
            // Drives the card's status line; mirrors the old "remote text only
            // while transmitting" gating
            isConnected = items[compat.address.uppercase()]?.isTransmissionActive == true,
            isSaved = true,
            isPaired = compat.isPaired,
        )
    }

    fun resolve(info: BluetoothDeviceInfo): AssociatedDeviceCompat? =
        associatedDevices.firstOrNull { it.address.uppercase() == info.identifier }

    SharedDeviceList(
        devices = devices,
        items = items,
        showKeepAliveHint = Build.VERSION.SDK_INT < Build.VERSION_CODES.S,
        // Read on every recomposition so a toggle in settings applies on return
        hapticsEnabled = PreferencesManager.isHapticsEnabled(context),
        contentPadding = PaddingValues(top = 16.dp, bottom = 16.dp),
        onConnect = { }, // tap-to-pair path: Android never lists unsaved devices
        onTriggerRemoteShutter = { info ->
            if (!SCREENSHOT_MODE) {
                val shutterIntent = Intent(
                    context.applicationContext,
                    LocationSenderService::class.java
                ).apply {
                    action = SonyBluetoothConstants.ACTION_TRIGGER_SHUTTER_SEQUENCE
                    putExtra("address", info.identifier)
                }
                context.startService(shutterIntent)
            }
        },
        onDelete = { info -> resolve(info)?.let(onDisassociate) },
        onOpenDetails = { info -> resolve(info)?.let(onConnect) },
    )
}

@Composable
private fun EmptyDevicesCard() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                painterResource(Res.drawable.camera_24px),
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(Res.string.no_devices_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(Res.string.no_devices_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
