package com.saschl.cameragps.ui

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.android_12_requires_keep_alive
import cameragps.sharednew.generated.resources.associated_devices
import cameragps.sharednew.generated.resources.device_icon
import cameragps.sharednew.generated.resources.keyboard_arrow_right_24px
import cameragps.sharednew.generated.resources.no_devices_message
import cameragps.sharednew.generated.resources.no_devices_title
import cameragps.sharednew.generated.resources.not_paired_tap_to_pair_again
import cameragps.sharednew.generated.resources.remote_feature_active
import cameragps.sharednew.generated.resources.remote_feature_inactive
import cameragps.sharednew.generated.resources.show_details
import com.sasch.cameragps.sharednew.bluetooth.SonyBluetoothConstants
import com.sasch.cameragps.sharednew.database.LogDatabase
import com.sasch.cameragps.sharednew.database.getDatabaseBuilder
import com.sasch.cameragps.sharednew.ui.TransmissionDot
import com.saschl.cameragps.R
import com.saschl.cameragps.service.AssociatedDeviceCompat
import com.saschl.cameragps.service.LocationSenderService
import com.saschl.cameragps.ui.device.SCREENSHOT_MODE
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource


@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AssociatedDevicesList(
    associatedDevices: List<AssociatedDeviceCompat>,
    onConnect: (AssociatedDeviceCompat) -> Unit
) {
    val context = LocalContext.current
    val cameraDeviceDAO = LogDatabase.getRoomDatabase(
        getDatabaseBuilder(context.applicationContext)
    ).cameraDeviceDao()

    val enableServer = remember {
        LocationSenderService.activeTransmissions
    }
    val remoteFeatureStatus = remember {
        LocationSenderService.remoteFeatureActive
    }
    Column {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            stickyHeader {
                Text(
                    text = stringResource(Res.string.associated_devices),
                    modifier = Modifier.padding(8.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            if (associatedDevices.isEmpty()) {
                item {
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
                                painterResource(R.drawable.baseline_photo_camera_24),
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
            }

            items(associatedDevices, key = { device -> device.address }) { device ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp, bottom = 12.dp, end = 16.dp)
                        .clickable(
                            true,
                            onClick = {
                                onConnect(device)
                            }),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    var isAlwaysOnEnabled by remember(device.address) { mutableStateOf(true) }

                    val isTransmissionRunning = enableServer[device.address]
                    val isRemoteFeatureActive =
                        remoteFeatureStatus[device.address.uppercase()] == true


                    LaunchedEffect(device.address) {
                        isAlwaysOnEnabled = cameraDeviceDAO.isDeviceAlwaysOnEnabled(device.address)
                    }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .weight(0.8f)
                            .padding(start = 18.dp),
                        horizontalAlignment = Alignment.Start,
                        verticalArrangement = Arrangement.Center,

                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {

                            TransmissionDot(
                                isRunning = isTransmissionRunning ?: false,
                            )
                            Text(
                                fontWeight = FontWeight.Bold,
                                text = device.name
                            )
                        }
                        Row() {
                            if (!device.isPaired) {
                                Text(
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    text = stringResource(Res.string.not_paired_tap_to_pair_again),
                                )
                            }

                            if (isTransmissionRunning == true) {
                                Text(
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isRemoteFeatureActive) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    text = if (isRemoteFeatureActive) {
                                        stringResource(Res.string.remote_feature_active)
                                    } else {
                                        stringResource(Res.string.remote_feature_inactive)
                                    }
                                )
                            }
                        }


                    }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .weight(.2f),
                    ) {



                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                            && !isAlwaysOnEnabled
                        ) {
                            Text(
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                text = stringResource(Res.string.android_12_requires_keep_alive),
                            )
                        }
                    }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .weight(0.4f),
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            IconButton(
                                enabled = isRemoteFeatureActive,
                                onClick = {
                                    if (!SCREENSHOT_MODE) {
                                        val shutterIntent = Intent(
                                            context.applicationContext,
                                            LocationSenderService::class.java
                                        ).apply {
                                            action =
                                                SonyBluetoothConstants.ACTION_TRIGGER_SHUTTER_SEQUENCE
                                            putExtra("address", device.address.uppercase())
                                        }
                                        context.startService(shutterIntent)
                                    }
                                }) {
                                Icon(
                                    painterResource(R.drawable.baseline_photo_camera_24),
                                    contentDescription = stringResource(Res.string.device_icon)
                                )
                            }
                            Icon(
                                painterResource(Res.drawable.keyboard_arrow_right_24px),
                                contentDescription = stringResource(Res.string.show_details)
                            )
                        }
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(top = 2.dp),
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

