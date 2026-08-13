package com.saschl.cameragps

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.sasch.cameragps.sharednew.database.LogDatabase
import com.sasch.cameragps.sharednew.database.getDatabaseBuilder
import com.sasch.cameragps.sharednew.database.logging.LogRepository
import com.sasch.cameragps.sharednew.ui.logs.SharedLogViewerScreen
import com.sasch.cameragps.sharednew.ui.theme.CameraGpsTheme
import com.saschl.cameragps.service.LocationSenderService
import com.saschl.cameragps.ui.EnhancedLocationPermissionBox
import com.saschl.cameragps.ui.HelpScreen
import com.saschl.cameragps.ui.SentryConsentDialog
import com.saschl.cameragps.ui.TroubleshootingScreen
import com.saschl.cameragps.ui.WelcomeScreen
import com.saschl.cameragps.ui.device.CameraDeviceManager
import com.saschl.cameragps.ui.device.SCREENSHOT_MODE
import com.saschl.cameragps.ui.settings.SettingsScreen
import com.saschl.cameragps.utils.CrashReporting
import com.saschl.cameragps.utils.PreferencesManager
import com.saschl.cameragps.utils.logging.AndroidLogFormatter
import kotlinx.serialization.Serializable
import timber.log.Timber

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            CameraGpsTheme {
                AppContent()
            }
        }
    }

    @Composable
    private fun AppContent() {
        val context = LocalContext.current
        val cameraDeviceDAO =
            LogDatabase.getRoomDatabase(getDatabaseBuilder(context)).cameraDeviceDao()
        val logRepository = remember(context) { LogRepository(getDatabaseBuilder(context)) }
        val lifecycleState by ProcessLifecycleOwner.get().lifecycle.currentStateFlow.collectAsState()

        val startDestination = remember {
            if (PreferencesManager.isFirstLaunch(context)) {
                AppDestination.Welcome
            } else {
                AppDestination.Devices
            }
        }
        val backStack = rememberNavBackStack(startDestination)


        val view = LocalView.current
        val darkTheme = isSystemInDarkTheme()
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars =
                !darkTheme
        }

        fun popBackStackIfPossible() {
            if (backStack.size > 1) {
                backStack.removeAt(backStack.lastIndex)
            } else {
                Timber.d("Ignoring back press on root destination")
            }
        }

        fun resetBackStackTo(destination: AppDestination) {
            if (backStack.isEmpty()) {
                backStack.add(destination)
                return
            }

            backStack[0] = destination
            while (backStack.size > 1) {
                backStack.removeAt(backStack.lastIndex)
            }
        }

        LaunchedEffect(lifecycleState) {
            Timber.d("Lifecycle state changed: $lifecycleState")
            when (lifecycleState) {
                Lifecycle.State.RESUMED -> {
                    if (PreferencesManager.isFirstLaunch(context)) {
                        resetBackStackTo(AppDestination.Welcome)
                    }
                }
                else -> { /* No action needed */ }
            }
        }

        LaunchedEffect(lifecycleState) {
            when (lifecycleState) {
                Lifecycle.State.RESUMED -> {
                    Timber.d("App started, will resume transmission for configured devices")
                    cameraDeviceDAO.getAllCameraDevices().forEach {
                        val shouldTransmissionStart =
                            it.deviceEnabled
                                    && it.alwaysOnEnabled
                                    && PreferencesManager.isAppEnabled(context.applicationContext)
                        if (shouldTransmissionStart) {
                            Timber.d("Resuming location transmission for device ${it.mac}")
                            val intent = Intent(context, LocationSenderService::class.java)
                            intent.putExtra("address", it.mac.uppercase())
                            context.startForegroundService(intent)
                        }
                    }
                }

                else -> { /* No action needed */ }
            }
        }

        var showSentryDialog by remember {
            mutableStateOf(
                CrashReporting.AVAILABLE && !SCREENSHOT_MODE &&
                        !PreferencesManager.isSentryConsentDialogDismissed(context)
            )
        }
        var forceDonationDialogThisLaunch by remember {
            mutableStateOf(
                PreferencesManager.consumeForceDonationDialogOnNextAppStart(context)
            )
        }

        NavDisplay(
            backStack = backStack,
            onBack = {
                popBackStackIfPossible()
            }
        ) { destination ->
            when (destination) {
                AppDestination.Welcome -> {
                    NavEntry(AppDestination.Welcome) {
                        WelcomeScreen(
                            onGetStarted = {
                                PreferencesManager.setFirstLaunchCompleted(context)
                                resetBackStackTo(AppDestination.Devices)
                                Timber.i("Welcome screen completed, navigating to main app")
                            }
                        )
                    }
                }

                AppDestination.Settings -> {
                    NavEntry(AppDestination.Settings) {
                        SettingsScreen(
                            onBackClick = {
                                popBackStackIfPossible()
                            }
                        )
                    }
                }

                AppDestination.Help -> {
                    NavEntry(AppDestination.Help) {
                        HelpScreen(
                            onBackClick = {
                                popBackStackIfPossible()
                            },
                            onTroubleshootingClick = {
                                backStack.add(AppDestination.Troubleshooting)
                            }
                        )
                    }
                }

                AppDestination.Troubleshooting -> {
                    NavEntry(AppDestination.Troubleshooting) {
                        TroubleshootingScreen(
                            onBackClick = {
                                popBackStackIfPossible()
                            }
                        )
                    }
                }

                AppDestination.Devices -> {
                    NavEntry(AppDestination.Devices) {
                        LaunchedEffect(Unit) {
                            showSentryDialog = CrashReporting.AVAILABLE && !SCREENSHOT_MODE &&
                                    !PreferencesManager.isSentryConsentDialogDismissed(context)
                        }


                        EnhancedLocationPermissionBox {
                            CameraDeviceManager(
                                forceShowDonationDialogOnEnter = forceDonationDialogThisLaunch,
                                onForceDonationDialogConsumed = {
                                    forceDonationDialogThisLaunch = false
                                },
                                onSettingsClick = {
                                    backStack.add(AppDestination.Settings)
                                },
                                onHelpClick = {
                                    backStack.add(AppDestination.Help)
                                },
                                onTroubleshootingClick = {
                                    backStack.add(AppDestination.Troubleshooting)
                                },
                                onLogsClick = {
                                    backStack.add(AppDestination.Logs)
                                }
                            )
                        }


                        if (showSentryDialog) {
                            SentryConsentDialog(
                                onDismiss = {
                                    showSentryDialog = false
                                }
                            )
                        }
                    }
                }

                AppDestination.Logs -> {
                    NavEntry(AppDestination.Logs) {
                        val logFormatter =
                            remember(logRepository) { AndroidLogFormatter(logRepository) }
                        SharedLogViewerScreen(
                            logFormatter,
                            logRepository = logRepository,
                            onBackClick = {
                                popBackStackIfPossible()
                            }
                        )
                    }
                }

                else -> {
                    NavEntry(destination) {
                        // Unknown destination fallback
                    }
                }
            }
        }
    }
}

private sealed interface AppDestination : NavKey {
    @Serializable
    data object Welcome : AppDestination
    @Serializable
    data object Devices : AppDestination
    @Serializable
    data object Settings : AppDestination

    @Serializable
    data object Help : AppDestination

    @Serializable
    data object Troubleshooting : AppDestination

    @Serializable
    data object Logs : AppDestination
}
