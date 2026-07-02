package com.sasch.cameragps.sharednew

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.baseline_view_list_24
import cameragps.sharednew.generated.resources.cancel_button
import cameragps.sharednew.generated.resources.donation_dialog_confirm
import cameragps.sharednew.generated.resources.donation_dialog_dismiss
import cameragps.sharednew.generated.resources.donation_dialog_message
import cameragps.sharednew.generated.resources.donation_dialog_title
import cameragps.sharednew.generated.resources.header_device_list
import cameragps.sharednew.generated.resources.info_24px
import cameragps.sharednew.generated.resources.open_location_settings
import cameragps.sharednew.generated.resources.open_settings_for_precise_location
import cameragps.sharednew.generated.resources.precise_location
import cameragps.sharednew.generated.resources.settings
import cameragps.sharednew.generated.resources.settings_24px
import cameragps.sharednew.generated.resources.view_logs
import cameragps.sharednew.generated.resources.welcome_get_started_button
import cameragps.sharednew.generated.resources.welcome_settings_note
import cameragps.sharednew.generated.resources.welcome_subtitle
import cameragps.sharednew.generated.resources.welcome_title
import com.diamondedge.logging.KmLogging
import com.diamondedge.logging.LogLevel
import com.diamondedge.logging.VariableLogLevel
import com.sasch.cameragps.sharednew.bluetooth.IosBluetoothController
import com.sasch.cameragps.sharednew.database.getDatabaseBuilder
import com.sasch.cameragps.sharednew.database.logging.DatabaseLogger
import com.sasch.cameragps.sharednew.database.logging.LogRepository
import com.sasch.cameragps.sharednew.logging.IosLogFormatter
import com.sasch.cameragps.sharednew.ui.device.SharedDevicesScreen
import com.sasch.cameragps.sharednew.ui.logs.SharedLogViewerScreen
import com.sasch.cameragps.sharednew.ui.welcome.SharedWelcomeScreen
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UIKit.UIApplicationState.UIApplicationStateActive

internal enum class IosScreen {
    Welcome,
    Devices,
    DeviceDetails,
    Settings,
    Help,
    Logs,
}

@Composable
internal fun CameraGpsIosApp() {
    val bluetoothController = IosBluetoothController
    val realDevices by bluetoothController.devices.collectAsState()
    val devices = if (SCREENSHOT_MODE) mockDevices else realDevices
    val scope = rememberCoroutineScope()
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val logRepository = remember { LogRepository(getDatabaseBuilder()) }
    var currentScreen by remember {
        mutableStateOf(
            if (IosAppPreferences.showWelcomeOnLaunch()) IosScreen.Welcome else IosScreen.Devices
        )
    }
    var isAppEnabled by remember { mutableStateOf(IosAppPreferences.isAppEnabled()) }
    var autoScanEnabled by remember { mutableStateOf(IosAppPreferences.isAutoScanEnabled()) }
    var isAppInForeground by remember {
        mutableStateOf(
            UIApplication.sharedApplication.applicationState == UIApplicationStateActive
        )
    }
    var showDonationDialog by remember { mutableStateOf(false) }
    var showRequestPreciseAccuracyPermissionDialog by remember { mutableStateOf(false) }
    var scrollToTipJarOnSettingsOpen by remember { mutableStateOf(false) }
    var forceDonationDialogThisLaunch by remember { mutableStateOf(false) }
    var selectedDeviceIdentifier by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        val center = NSNotificationCenter.defaultCenter
        val backgroundObserver = center.addObserverForName(
            name = UIApplicationDidEnterBackgroundNotification,
            `object` = null,
            queue = null
        ) { _ ->
            isAppInForeground = false
        }
        val activeObserver = center.addObserverForName(
            name = UIApplicationDidBecomeActiveNotification,
            `object` = null,
            queue = null
        ) { _ ->
            isAppInForeground = true
        }

        onDispose {
            center.removeObserver(backgroundObserver)
            center.removeObserver(activeObserver)
        }
    }

    LaunchedEffect(Unit) {
        KmLogging.setLoggers(
            DatabaseLogger(
                logRepository,
                VariableLogLevel(LogLevel.valueOf(IosAppPreferences.getLogLevel()))
            )
        )
        forceDonationDialogThisLaunch = IosAppPreferences.consumeForceDonationDialogOnNextAppStart()
    }

    LaunchedEffect(lifecycleState) {
        when (lifecycleState) {
            Lifecycle.State.RESUMED -> {
                if (!bluetoothController.hasPreciseAccuracyAuthorization()) {
                    showRequestPreciseAccuracyPermissionDialog = true
                }
            }

            else -> {}
        }

    }
    LaunchedEffect(currentScreen, isAppEnabled, autoScanEnabled, isAppInForeground) {
        if (SCREENSHOT_MODE) return@LaunchedEffect
        if (currentScreen == IosScreen.Devices && isAppEnabled && autoScanEnabled && isAppInForeground) {
            bluetoothController.startScan()
        } else {
            bluetoothController.stopScan()
        }
    }

    LaunchedEffect(currentScreen, isAppInForeground, devices, showDonationDialog) {
        if (SCREENSHOT_MODE || showDonationDialog) return@LaunchedEffect
        if (
            currentScreen == IosScreen.Devices &&
            isAppInForeground &&
            devices.isNotEmpty() &&
            IosAppPreferences.donationHintLastShownDaysAgo(initialize = true) >= 30 &&
            IosAppPreferences.donationHintShownTimes() < 1
        ) {
            IosAppPreferences.setDonationHintShownNow()
            IosAppPreferences.increaseDonationHintShownTimes()
            showDonationDialog = true
        }
    }

    LaunchedEffect(
        currentScreen,
        isAppInForeground,
        showDonationDialog,
        forceDonationDialogThisLaunch
    ) {
        if (SCREENSHOT_MODE || showDonationDialog || !forceDonationDialogThisLaunch) return@LaunchedEffect
        if (currentScreen == IosScreen.Devices && isAppInForeground) {
            showDonationDialog = true
            forceDonationDialogThisLaunch = false
        }
    }

    when (currentScreen) {
        IosScreen.Welcome -> {
            SharedWelcomeScreen(
                title = stringResource(Res.string.welcome_title),
                subtitle = stringResource(Res.string.welcome_subtitle),
                getStartedText = stringResource(Res.string.welcome_get_started_button),
                settingsNote = stringResource(Res.string.welcome_settings_note),
                firstStepFeatures = firstStepFeatures(),
                secondStepFeatures = emptyList(),
                onGetStarted = {
                    IosAppPreferences.setShowWelcomeOnLaunch(false)
                    currentScreen = IosScreen.Devices
                },
                iconContent = {
                    Text(
                        text = "📷",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                },
            )
        }

        IosScreen.Devices -> {
            SharedDevicesScreen(
                title = stringResource(Res.string.header_device_list),
                topBarActions = {
                    IconButton(onClick = { currentScreen = IosScreen.Help }) {
                        Icon(
                            painterResource(Res.drawable.info_24px),
                            contentDescription = stringResource(Res.string.settings)
                        )
                    }
                    IconButton(onClick = { currentScreen = IosScreen.Logs }) {
                        Icon(
                            painterResource(Res.drawable.baseline_view_list_24),
                            contentDescription = stringResource(Res.string.view_logs)
                        )
                    }
                    IconButton(onClick = { currentScreen = IosScreen.Settings }) {
                        Icon(
                            painterResource(Res.drawable.settings_24px),
                            contentDescription = stringResource(Res.string.settings)
                        )
                    }
                },
            ) {
                DeviceListContent(
                    devices = devices,
                    isAppEnabled = isAppEnabled,
                    isScanning = autoScanEnabled,
                    onOpenSettings = { currentScreen = IosScreen.Settings },
                    onOpenHelp = { currentScreen = IosScreen.Help },
                    onConnect = { device ->
                        scope.launch {
                            if (!device.isConnected) {
                                bluetoothController.connect(device.identifier)
                            }
                        }
                    },
                    onTriggerRemoteShutter = { device ->
                        scope.launch {
                            bluetoothController.triggerShutterSequence(device.identifier)
                        }
                    },
                    onDelete = { device ->
                        scope.launch {
                            bluetoothController.forgetDevice(device.identifier)
                        }
                    },
                    onOpenDetails = { device ->
                        selectedDeviceIdentifier = device.identifier
                        currentScreen = IosScreen.DeviceDetails
                    },
                )
            }
        }

        IosScreen.DeviceDetails -> {
            val selectedDevice = devices.firstOrNull { it.identifier == selectedDeviceIdentifier }
            if (selectedDevice == null) {
                LaunchedEffect(Unit) {
                    currentScreen = IosScreen.Devices
                }
            } else {
                IosDeviceDetailScreen(
                    device = selectedDevice,
                    onBackClick = { currentScreen = IosScreen.Devices },
                )
            }
        }

        IosScreen.Settings -> {
            IosSettingsScreen(
                isAppEnabled = isAppEnabled,
                autoScanEnabled = autoScanEnabled,
                scrollToTipJarOnOpen = scrollToTipJarOnSettingsOpen,
                onBackClick = { currentScreen = IosScreen.Devices },
                onOpenHelp = { currentScreen = IosScreen.Help },
                onAppEnabledChange = { enabled ->
                    isAppEnabled = enabled
                    IosAppPreferences.setAppEnabled(enabled)
                    scope.launch {
                        bluetoothController.applyAppEnabledState(enabled)
                    }
                },
                onAutoScanEnabledChange = { enabled ->
                    autoScanEnabled = enabled
                    IosAppPreferences.setAutoScanEnabled(enabled)
                },
                onShowWelcomeAgain = {
                    IosAppPreferences.setShowWelcomeOnLaunch(true)
                    currentScreen = IosScreen.Welcome
                },
                onChangeLogLevel = { level ->
                    KmLogging.setLoggers(DatabaseLogger(logRepository, VariableLogLevel(level)))
                },
                onTipJarScrollConsumed = {
                    scrollToTipJarOnSettingsOpen = false
                }
            )
        }

        IosScreen.Help -> {
            IosHelpScreen(
                onBackClick = { currentScreen = IosScreen.Devices }
            )
        }

        IosScreen.Logs -> {
            val logFormatter = remember(logRepository) { IosLogFormatter(logRepository) }
            SharedLogViewerScreen(
                logFormatter = logFormatter,
                logRepository = logRepository,
                onBackClick = { currentScreen = IosScreen.Devices }
            )
        }
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
                        scrollToTipJarOnSettingsOpen = true
                        currentScreen = IosScreen.Settings
                    }
                ) {
                    Text(text = stringResource(Res.string.donation_dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDonationDialog = false }) {
                    Text(text = stringResource(Res.string.donation_dialog_dismiss))
                }
            }
        )
    }
    if (showRequestPreciseAccuracyPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showDonationDialog = false },
            title = { Text(text = stringResource(Res.string.precise_location)) },
            text = { Text(text = stringResource(Res.string.open_settings_for_precise_location)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRequestPreciseAccuracyPermissionDialog = false
                        val settingsUrl = NSURL.URLWithString(UIApplicationOpenSettingsURLString)

                        if (settingsUrl != null && UIApplication.sharedApplication.canOpenURL(
                                settingsUrl
                            )
                        ) {
                            UIApplication.sharedApplication.openURL(
                                settingsUrl,
                                emptyMap<Any?, Any>(),
                                {})
                        }
                    }
                ) {
                    Text(text = stringResource(Res.string.open_location_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRequestPreciseAccuracyPermissionDialog = false }) {
                    Text(text = stringResource(Res.string.cancel_button))
                }

            }
        )
    }
}
