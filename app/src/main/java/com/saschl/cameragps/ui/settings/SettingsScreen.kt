package com.saschl.cameragps.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.back
import cameragps.sharednew.generated.resources.battery_optimization_settings_title
import cameragps.sharednew.generated.resources.enable_sentry_description
import cameragps.sharednew.generated.resources.event_sounds_open_section_description
import cameragps.sharednew.generated.resources.event_sounds_title
import cameragps.sharednew.generated.resources.keyboard_arrow_right_24px
import cameragps.sharednew.generated.resources.location_provider_hint
import cameragps.sharednew.generated.resources.location_provider_title
import cameragps.sharednew.generated.resources.sentry_settings
import cameragps.sharednew.generated.resources.settings
import com.sasch.cameragps.sharednew.ui.settings.SharedSettingsScreen
import com.saschl.cameragps.R
import com.saschl.cameragps.service.location.LOCATION_PROVIDER_SELECTABLE
import com.saschl.cameragps.ui.ReviewHintDebugPanel
import com.saschl.cameragps.utils.CrashReporting
import com.saschl.cameragps.utils.PreferencesManager
import kotlinx.serialization.Serializable
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
) {
    val settingsBackStack = rememberNavBackStack(SettingsDestination.Overview)

    fun navigateBack() {
        if (settingsBackStack.lastIndex > 0) {
            settingsBackStack.removeAt(settingsBackStack.lastIndex)
        } else {
            onBackClick()
        }
    }

    NavDisplay(
        backStack = settingsBackStack,
        onBack = { navigateBack() }
    ) { destination ->
        when (destination) {
            SettingsDestination.Overview -> {
                NavEntry(SettingsDestination.Overview) {
                    SettingsOverviewScreen(
                        onBackClick = { navigateBack() },
                        onOpenDestination = { nextDestination ->
                            settingsBackStack.add(nextDestination)
                        }
                    )
                }
            }

            SettingsDestination.LocationProvider -> {
                NavEntry(SettingsDestination.LocationProvider) {
                    LocationProviderSettingsScreen(onBackClick = { navigateBack() })
                }
            }

            SettingsDestination.EventSounds -> {
                NavEntry(SettingsDestination.EventSounds) {
                    EventSoundsScreen(onBackClick = { navigateBack() })
                }
            }

            SettingsDestination.BatteryOptimization -> {
                NavEntry(SettingsDestination.BatteryOptimization) {
                    BatteryOptimizationSettingsScreen(onBackClick = { navigateBack() })
                }
            }

            /*    SettingsDestination.Language -> {
                    NavEntry(SettingsDestination.Language) {
                        LanguageSettingsSubScreen(onBackClick = { navigateBack() })
                    }
                }*/

            SettingsDestination.Sentry -> {
                NavEntry(SettingsDestination.Sentry) {
                    SentrySettingsSubScreen(onBackClick = { navigateBack() })
                }
            }

            else -> {
                NavEntry(destination) {}
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsOverviewScreen(
    onBackClick: () -> Unit,
    onOpenDestination: (SettingsDestination) -> Unit,
) {
    var debugPanelCounter by remember { mutableIntStateOf(0) }

    SharedSettingsScreen(
        title = stringResource(Res.string.settings),
        onBackClick = onBackClick,
        onTitleClick = { debugPanelCounter++ },
        navigationIcon = {
            Icon(
                painterResource(R.drawable.arrow_back_24px),
                contentDescription = stringResource(Res.string.back)
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                AppControlsInlineSection()
            }
            item {
                LogLevelSettingsCard()
            }
            if (LOCATION_PROVIDER_SELECTABLE) {
                item {
                    SettingsSectionNavigation(
                        title = stringResource(Res.string.location_provider_title),
                        description = stringResource(Res.string.location_provider_hint),
                        onClick = { onOpenDestination(SettingsDestination.LocationProvider) }
                    )
                }
            }
            item {
                SettingsSectionNavigation(
                    title = stringResource(Res.string.event_sounds_title),
                    description = stringResource(Res.string.event_sounds_open_section_description),
                    onClick = { onOpenDestination(SettingsDestination.EventSounds) }
                )
            }
            item {
                SettingsSectionNavigation(
                    title = stringResource(Res.string.battery_optimization_settings_title),
                    onClick = { onOpenDestination(SettingsDestination.BatteryOptimization) }
                )
            }
            item {
                LanguageSettingsCard()
            }
            if (CrashReporting.AVAILABLE) {
                item {
                    SettingsSectionNavigation(
                        title = stringResource(Res.string.sentry_settings),
                        description = stringResource(Res.string.enable_sentry_description),
                        onClick = { onOpenDestination(SettingsDestination.Sentry) }
                    )
                }
            }

            if (debugPanelCounter >= 5) {
                item {
                    ReviewHintDebugPanel()
                }
                item {
                    DebugRestartReceiverCard()
                }
                item {
                    DebugDonationDialogCard()
                }
            }
        }
    }
}

@Composable
private fun AppControlsInlineSection() {
    val context = LocalContext.current
    var isAppEnabled by remember {
        mutableStateOf(PreferencesManager.isAppEnabled(context))
    }

    AppControlsCard(
        isAppEnabled = isAppEnabled,
        onAppEnabledChange = { enabled ->
            isAppEnabled = enabled
            PreferencesManager.setAppEnabled(context, enabled)
        }
    )
}

@Composable
private fun SettingsSectionNavigation(
    title: String,
    description: String? = null,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            ListItem(
                headlineContent = { Text(title) },
                supportingContent = description?.takeIf { it.isNotBlank() }?.let { value ->
                    { Text(value) }
                },
                trailingContent = {
                    Icon(
                        painter = painterResource(Res.drawable.keyboard_arrow_right_24px),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )
        }
    }
}

@Composable
private fun LocationProviderSettingsScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    var locationProvider by remember {
        mutableStateOf(PreferencesManager.getLocationProvider(context))
    }

    SettingsSectionScreen(
        title = stringResource(Res.string.location_provider_title),
        onBackClick = onBackClick
    ) {
        LocationProviderCard(
            locationProvider = locationProvider,
            onProviderChange = { provider ->
                locationProvider = provider
                PreferencesManager.setLocationProvider(context, provider)
            }
        )
    }
}

@Composable
private fun BatteryOptimizationSettingsScreen(onBackClick: () -> Unit) {
    SettingsSectionScreen(
        title = stringResource(Res.string.battery_optimization_settings_title),
        onBackClick = onBackClick
    ) {
        BatteryOptimizationCard()
    }
}


@Composable
private fun SentrySettingsSubScreen(onBackClick: () -> Unit) {
    SettingsSectionScreen(
        title = stringResource(Res.string.sentry_settings),
        onBackClick = onBackClick
    ) {
        SentrySettingsCard()
    }
}

private sealed interface SettingsDestination : NavKey {
    @Serializable
    data object Overview : SettingsDestination

    @Serializable
    data object LocationProvider : SettingsDestination

    @Serializable
    data object EventSounds : SettingsDestination

    @Serializable
    data object BatteryOptimization : SettingsDestination

    @Serializable
    data object Language : SettingsDestination


    @Serializable
    data object Sentry : SettingsDestination
}
