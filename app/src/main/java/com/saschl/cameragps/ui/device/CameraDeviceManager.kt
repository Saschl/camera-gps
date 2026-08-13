package com.saschl.cameragps.ui.device

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.companion.CompanionDeviceManager
import android.companion.ObservingDevicePresenceRequest
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.dismiss_button
import cameragps.sharednew.generated.resources.donation_dialog_confirm
import cameragps.sharednew.generated.resources.donation_dialog_dismiss
import cameragps.sharednew.generated.resources.donation_dialog_message
import cameragps.sharednew.generated.resources.donation_dialog_title
import cameragps.sharednew.generated.resources.guide_open_button
import cameragps.sharednew.generated.resources.pairing_failed_device_message
import cameragps.sharednew.generated.resources.pairing_failed_hint_camera_pairing
import cameragps.sharednew.generated.resources.pairing_failed_hint_intro
import cameragps.sharednew.generated.resources.pairing_failed_hint_pairing_mode
import cameragps.sharednew.generated.resources.pairing_failed_hint_phone_pairing
import cameragps.sharednew.generated.resources.pairing_failed_title
import com.google.android.play.core.review.ReviewException
import com.google.android.play.core.review.ReviewManagerFactory
import com.google.android.play.core.review.model.ReviewErrorCode
import com.sasch.cameragps.sharednew.database.LogDatabase
import com.sasch.cameragps.sharednew.database.devices.CameraDevice
import com.sasch.cameragps.sharednew.database.getDatabaseBuilder
import com.saschl.cameragps.AppServices
import com.saschl.cameragps.service.AssociatedDeviceCompat
import com.saschl.cameragps.service.BluetoothStateBroadcastReceiver
import com.saschl.cameragps.service.LocationSenderService
import com.saschl.cameragps.service.getAssociatedDevices
import com.saschl.cameragps.ui.EnhancedLocationPermissionBox
import com.saschl.cameragps.ui.pairing.startDevicePresenceObservation
import com.saschl.cameragps.utils.PreferencesManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import timber.log.Timber

@SuppressLint("MissingPermission")
@Composable
fun CameraDeviceManager(
    forceShowDonationDialogOnEnter: Boolean = false,
    onForceDonationDialogConsumed: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onHelpClick: () -> Unit = {},
    onTroubleshootingClick: () -> Unit = {},
    onLogsClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val deviceManager = context.getSystemService<CompanionDeviceManager>()
    val devicesDao = LogDatabase.getRoomDatabase(
        getDatabaseBuilder(context)
    ).cameraDeviceDao()
    val adapter = context.getSystemService<BluetoothManager>()?.adapter
    val locationManager = context.getSystemService<LocationManager>()
    var selectedDevice by remember {
        mutableStateOf<AssociatedDeviceCompat?>(null)
    }

    val manager = ReviewManagerFactory.create(context)

    val activity = LocalActivity.current

    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsState()

    var associatedDevices by remember {
        mutableStateOf(
            if (SCREENSHOT_MODE) {
                mockDevices
            } else {
                deviceManager!!.getAssociatedDevices(adapter!!)
            }
        )
    }

    var isBluetoothEnabled by remember {
        mutableStateOf(adapter?.isEnabled == true)
    }

    var isLocationEnabled by remember {
        mutableStateOf(
            locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true ||
                    locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
        )
    }

    var isReviewFlowActive by remember { mutableStateOf(false) }
    var showDonationDialog by remember { mutableStateOf(false) }
    val pairingFailedAddress by AppServices.from(context).pairingFailedDevice.collectAsState()
    // Suppresses re-showing the pairing-failure dialog for the same device: the failed
    // camera keeps auto-reconnecting and may exhaust its pairing retries repeatedly.
    // AppServices clears pairingFailedDevice on a successful handshake, which re-arms it.
    var dismissedPairingFailedAddress by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(pairingFailedAddress) {
        if (pairingFailedAddress == null) {
            dismissedPairingFailedAddress = null
        }
    }

    LaunchedEffect(associatedDevices) {
        if (SCREENSHOT_MODE) return@LaunchedEffect
        associatedDevices.forEach {
            devicesDao.insertDevice(
                CameraDevice(
                    deviceName = it.name,
                    mac = it.address.uppercase(),
                    alwaysOnEnabled = false,
                    deviceEnabled = true,
                )
            )
        }
    }

    // TODO refactor out of composable
    LaunchedEffect(lifecycleState) {
        if (SCREENSHOT_MODE) return@LaunchedEffect
        if (associatedDevices.isNotEmpty() && PreferencesManager.reviewHintLastShownDaysAgo(
                context.applicationContext,
                true
            ) >= 30
            && lifecycleState == Lifecycle.State.RESUMED && PreferencesManager.reviewHintShownTimes(
                context.applicationContext
            ) < 3
        ) {
            val request = manager.requestReviewFlow()
            PreferencesManager.setReviewHintShownNow(context.applicationContext)
            PreferencesManager.increaseReviewHintShownTimes(context.applicationContext)

            request.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    isReviewFlowActive = true
                    // We got the ReviewInfo object
                    val reviewInfo = task.result
                    val flow = manager.launchReviewFlow(activity!!, reviewInfo)
                    flow.addOnCompleteListener { _ ->
                        Timber.i("Review done!")
                        isReviewFlowActive = false
                    }
                } else {
                    // There was some problem, log or handle the error code.
                    @ReviewErrorCode val reviewErrorCode =
                        (task.exception as ReviewException).errorCode
                    Timber.e("Review flow failed with error code: $reviewErrorCode")
                    PreferencesManager.resetReviewHintShown(context.applicationContext)
                    PreferencesManager.decreaseReviewHintShownTimes(context.applicationContext)
                }
            }
        }
    }

    LaunchedEffect(lifecycleState, associatedDevices, isReviewFlowActive) {
        if (SCREENSHOT_MODE) return@LaunchedEffect
        if (!isReviewFlowActive &&
            associatedDevices.isNotEmpty() &&
            PreferencesManager.donationHintLastShownDaysAgo(
                context.applicationContext,
                true
            ) >= 30 &&
            lifecycleState == Lifecycle.State.RESUMED &&
            PreferencesManager.donationHintShownTimes(context.applicationContext) < 1
        ) {
            PreferencesManager.setDonationHintShownNow(context.applicationContext)
            PreferencesManager.increaseDonationHintShownTimes(context.applicationContext)
            showDonationDialog = true
        }
    }

    LaunchedEffect(forceShowDonationDialogOnEnter, lifecycleState, showDonationDialog) {
        if (SCREENSHOT_MODE) return@LaunchedEffect
        if (!forceShowDonationDialogOnEnter || showDonationDialog) return@LaunchedEffect
        if (lifecycleState == Lifecycle.State.RESUMED) {
            showDonationDialog = true
            onForceDonationDialogConsumed()
        }
    }

    DisposableEffect(context) {
        val bluetoothReceiver = BluetoothStateBroadcastReceiver { enabled ->
            isBluetoothEnabled = enabled
        }

        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        ContextCompat.registerReceiver(
            context.applicationContext,
            bluetoothReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        onDispose {
            context.applicationContext.unregisterReceiver(bluetoothReceiver)
        }
    }

    LaunchedEffect(lifecycleState) {
        if (SCREENSHOT_MODE) return@LaunchedEffect
        when (lifecycleState) {
            Lifecycle.State.RESUMED -> {
                associatedDevices = deviceManager!!.getAssociatedDevices(adapter!!)
                isBluetoothEnabled = adapter.isEnabled == true
                isLocationEnabled =
                    locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true ||
                            locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
            }

            else -> { /* No action needed */
            }
        }
    }

    if (deviceManager == null || adapter == null) {
        Text(text = "No Companion device manager found. The device does not support it.")
    } else {
        // Shared by the detail screen's Remove button and the list's swipe-to-delete
        val disassociateDevice: (AssociatedDeviceCompat) -> Unit = { device ->
            associatedDevices.find { ass -> ass.address == device.address }
                ?.let { foundDevice ->
                    Timber.i("Disassociating device: ${foundDevice.name} (${foundDevice.address})")
                    scope.launch {

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                            deviceManager.stopObservingDevicePresence(
                                ObservingDevicePresenceRequest.Builder()
                                    .setAssociationId(foundDevice.id)
                                    .build()
                            )
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            @Suppress("DEPRECATION")
                            deviceManager.stopObservingDevicePresence(
                                foundDevice.address
                            )
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            deviceManager.disassociate(foundDevice.id)
                        } else {
                            @Suppress("DEPRECATION")
                            deviceManager.disassociate(foundDevice.address)
                        }

                        val serviceIntent = Intent(
                            context.applicationContext,
                            LocationSenderService::class.java
                        ).apply {
                            action =
                                com.sasch.cameragps.sharednew.bluetooth.SonyBluetoothConstants.ACTION_REQUEST_SHUTDOWN
                        }
                        serviceIntent.putExtra(
                            "address",
                            foundDevice.address.uppercase()
                        )
                        context.startService(serviceIntent)

                        devicesDao.deleteDevice(CameraDevice(foundDevice.address.uppercase()))

                        associatedDevices =
                            deviceManager.getAssociatedDevices(adapter)
                    }
                    selectedDevice = null
                }
        }

        Box {
            if (selectedDevice == null) {
                EnhancedLocationPermissionBox {
                    DevicesScreen(
                        deviceManager = deviceManager,
                        isBluetoothEnabled = isBluetoothEnabled,
                        isLocationEnabled = isLocationEnabled,
                        associatedDevices = associatedDevices,
                        onDeviceAssociated = {
                            if (!SCREENSHOT_MODE) {
                                scope.launch {
                                    devicesDao.insertDevice(
                                        CameraDevice(
                                            deviceName = it.name,
                                            mac = it.address.uppercase(),
                                            alwaysOnEnabled = false,
                                            deviceEnabled = true,
                                        )
                                    )
                                    delay(1000) // give the system a short time to breathe
                                    startDevicePresenceObservation(deviceManager, it)
                                    // Refresh the devices list to update pairing state
                                    associatedDevices = deviceManager.getAssociatedDevices(adapter)
                                }
                            }
                        },
                        onConnect = { device ->
                            if (!SCREENSHOT_MODE) {
                                selectedDevice = device
                            }
                        },
                        onDisassociate = disassociateDevice,
                        onSettingsClick = onSettingsClick,
                        onHelpClick = onHelpClick,
                        onTroubleshootingClick = onTroubleshootingClick,
                        onLogsClick = onLogsClick
                    )
                }
            } else {
                EnhancedLocationPermissionBox {
                deviceManager.getAssociatedDevices(adapter)
                    .find { it.address == selectedDevice?.address }?.id?.let {

                    DeviceDetailScreen(
                                device = selectedDevice!!,
                                deviceManager = deviceManager,
                                associationId = it,
                        onDisassociate = disassociateDevice,
                                onClose = { selectedDevice = null },
                                onHelpClick = onHelpClick
                            )
                        }
                    }
            }
        }
        if (isReviewFlowActive) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(enabled = false) { }
            )
        }

        pairingFailedAddress
            ?.takeIf { !SCREENSHOT_MODE && it != dismissedPairingFailedAddress }
            ?.let { failedAddress ->
                val failedDeviceName = associatedDevices
                    .find { it.address.equals(failedAddress, ignoreCase = true) }
                    ?.name
                    ?: failedAddress
                AlertDialog(
                    onDismissRequest = { dismissedPairingFailedAddress = failedAddress },
                    title = { Text(text = stringResource(Res.string.pairing_failed_title)) },
                    text = {
                        Column(
                            modifier = Modifier.verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(
                                    Res.string.pairing_failed_device_message,
                                    failedDeviceName
                                )
                            )
                            Text(text = stringResource(Res.string.pairing_failed_hint_intro))
                            Text(text = stringResource(Res.string.pairing_failed_hint_pairing_mode))
                            Text(text = stringResource(Res.string.pairing_failed_hint_camera_pairing))
                            Text(text = stringResource(Res.string.pairing_failed_hint_phone_pairing))
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                dismissedPairingFailedAddress = failedAddress
                                onTroubleshootingClick()
                            }
                        ) { Text(text = stringResource(Res.string.guide_open_button)) }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            dismissedPairingFailedAddress = failedAddress
                        }) { Text(text = stringResource(Res.string.dismiss_button)) }
                    }
                )
            }

        if (showDonationDialog) {
            AlertDialog(
                onDismissRequest = { showDonationDialog = false },
                title = { Text(text = stringResource(Res.string.donation_dialog_title)) },
                text = { Text(text = stringResource(Res.string.donation_dialog_message)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showDonationDialog = false
                            val intent = Intent(Intent.ACTION_VIEW, BUY_ME_A_COFFEE_URL.toUri())
                            runCatching { context.startActivity(intent) }
                                .onFailure { Timber.w(it, "Failed to open donation link") }
                        }
                    ) { Text(text = stringResource(Res.string.donation_dialog_confirm)) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showDonationDialog = false
                    }) { Text(text = stringResource(Res.string.donation_dialog_dismiss)) }
                }
            )
        }
    }
}

private const val BUY_ME_A_COFFEE_URL = "https://buymeacoffee.com/wj8tism4dq"
