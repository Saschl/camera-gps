package com.saschl.cameragps.ui.device

import android.Manifest
import android.companion.CompanionDeviceManager
import android.companion.ObservingDevicePresenceRequest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresPermission
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.app_name_ui
import cameragps.sharednew.generated.resources.back
import cameragps.sharednew.generated.resources.device_name_with_address
import cameragps.sharednew.generated.resources.help_menu_item
import cameragps.sharednew.generated.resources.remove
import com.sasch.cameragps.sharednew.database.LogDatabase
import com.sasch.cameragps.sharednew.database.devices.CameraDeviceDAO
import com.sasch.cameragps.sharednew.database.getDatabaseBuilder
import com.sasch.cameragps.sharednew.ui.device.DeviceDetailContent
import com.sasch.cameragps.sharednew.ui.device.DeviceDetailDataSource
import com.sasch.cameragps.sharednew.ui.device.DeviceDetailViewModel
import com.saschl.cameragps.R
import com.saschl.cameragps.service.AssociatedDeviceCompat
import com.saschl.cameragps.service.LocationSenderService
import com.saschl.cameragps.ui.pairing.startDevicePresenceObservation
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import timber.log.Timber

private fun createAndroidDataSource(dao: CameraDeviceDAO): DeviceDetailDataSource {
    return object : DeviceDetailDataSource {
        override suspend fun ensureDeviceExists(deviceId: String, deviceName: String?) {
            // Android device records are managed by companion/pairing flows.
        }

        override suspend fun isDeviceEnabled(deviceId: String) = dao.isDeviceEnabled(deviceId)
        override suspend fun isAlwaysOnEnabled(deviceId: String) =
            dao.isDeviceAlwaysOnEnabled(deviceId)

        override suspend fun isRemoteControlEnabled(deviceId: String) =
            dao.isRemoteControlEnabled(deviceId)

        override suspend fun setDeviceEnabled(deviceId: String, enabled: Boolean) {
            dao.setDeviceEnabled(deviceId, enabled)
        }

        override suspend fun setAlwaysOnEnabled(deviceId: String, enabled: Boolean) {
            dao.setAlwaysOnEnabled(deviceId, enabled)
        }

        override suspend fun setRemoteControlEnabled(deviceId: String, enabled: Boolean) {
            dao.setRemoteControlEnabled(deviceId, enabled)
        }

        override suspend fun getHandshakeDelayMs(deviceId: String) =
            dao.getHandshakeDelayMs(deviceId) ?: 0L

        override suspend fun setHandshakeDelayMs(deviceId: String, delayMs: Long) {
            dao.setHandshakeDelayMs(deviceId, delayMs)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
fun DeviceDetailScreen(
    device: AssociatedDeviceCompat,
    deviceManager: CompanionDeviceManager,
    onDisassociate: (device: AssociatedDeviceCompat) -> Unit,
    associationId: Int,
    onClose: () -> Unit,
    onHelpClick: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val viewModel: DeviceDetailViewModel = viewModel(key = device.address) {
        val dao = LogDatabase.getRoomDatabase(getDatabaseBuilder(context)).cameraDeviceDao()
        DeviceDetailViewModel(
            dataSource = createAndroidDataSource(dao),
            serviceActions = AndroidDeviceDetailServiceActions(context.applicationContext),
        )
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = stringResource(Res.string.app_name_ui),
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            painter = painterResource(R.drawable.arrow_back_24px),
                            contentDescription = stringResource(Res.string.back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onHelpClick) {
                        Icon(
                            painter = painterResource(R.drawable.info_24px),
                            contentDescription = stringResource(Res.string.help_menu_item),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                )
            )
        }
    ) { innerPadding ->
        BackHandler { onClose() }

        DeviceDetailContent(
            viewModel = viewModel,
            deviceId = device.address,
            modifier = Modifier.padding(innerPadding),
            headerContent = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                ) {
                    Column(modifier = Modifier.weight(0.6f)) {
                        Text(
                            text = stringResource(
                                Res.string.device_name_with_address,
                                device.name,
                                device.address
                            )
                        )
                    }
                    Column(modifier = Modifier.weight(0.4f)) {
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { scope.launch { onDisassociate(device) } },
                            border = ButtonDefaults.outlinedButtonBorder().copy(
                                brush = SolidColor(MaterialTheme.colorScheme.error),
                            ),
                        ) {
                            Text(
                                text = stringResource(Res.string.remove),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            },
            onDeviceEnabledChanged = { enabled ->
                if (!enabled) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                        deviceManager.stopObservingDevicePresence(
                            ObservingDevicePresenceRequest.Builder()
                                .setAssociationId(associationId).build()
                        )
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        deviceManager.stopObservingDevicePresence(device.address)
                    }
                    Timber.i("Stopping LocationSenderService from detail for device ${device.address}")
                    val shutdownIntent =
                        Intent(context, LocationSenderService::class.java).apply {
                            action =
                                com.sasch.cameragps.sharednew.bluetooth.SonyBluetoothConstants.ACTION_REQUEST_SHUTDOWN
                            putExtra("address", device.address.uppercase())
                        }
                    context.startService(shutdownIntent)
                } else {
                    startDevicePresenceObservation(deviceManager, device)
                }
            },
        )
    }
}
