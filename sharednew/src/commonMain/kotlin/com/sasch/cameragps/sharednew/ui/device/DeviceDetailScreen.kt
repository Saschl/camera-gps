package com.sasch.cameragps.sharednew.ui.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.always_on_description
import cameragps.sharednew.generated.resources.enableConstantly
import cameragps.sharednew.generated.resources.enable_device
import cameragps.sharednew.generated.resources.enable_remote_control
import cameragps.sharednew.generated.resources.handshake_delay_description
import cameragps.sharednew.generated.resources.handshake_delay_off
import cameragps.sharednew.generated.resources.handshake_delay_seconds
import cameragps.sharednew.generated.resources.handshake_delay_title
import cameragps.sharednew.generated.resources.hint_if_issues_after_switching
import cameragps.sharednew.generated.resources.remote_control_hint
import com.sasch.cameragps.sharednew.util.KotlinPlatform
import com.sasch.cameragps.sharednew.util.currentPlatform
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * Shared device detail content that displays toggle rows for device settings.
 * Platform hosts wrap this with their own scaffold/toolbar.
 */
@Composable
fun DeviceDetailContent(
    viewModel: DeviceDetailViewModel,
    deviceId: String,
    deviceName: String? = null,
    modifier: Modifier = Modifier,
    headerContent: @Composable (() -> Unit)? = null,
    onDeviceEnabledChanged: ((Boolean) -> Unit)? = null,
) {
    val state = viewModel.uiState.collectAsState().value

    LaunchedEffect(deviceId) {
        viewModel.load(deviceId, deviceName)
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        if (headerContent != null) {
            item { headerContent() }
        }

        item {
            DeviceToggleRow(
                title = stringResource(Res.string.enable_device),
                checked = state.isDeviceEnabled,
                enabled = !state.isAlwaysOnEnabled && state.buttonEnabled,
                onCheckedChange = { enabled ->
                    viewModel.setDeviceEnabled(enabled, deviceId, onDeviceEnabledChanged)
                },
            )
        }


        if (currentPlatform == KotlinPlatform.Android) {


            item {
                DeviceToggleRow(
                    title = stringResource(Res.string.enableConstantly),
                    checked = state.isAlwaysOnEnabled,
                    enabled = state.isDeviceEnabled && state.buttonEnabled,
                    onCheckedChange = { enabled ->
                        viewModel.setAlwaysOnEnabled(enabled, deviceId)
                    },
                )
            }
        }

        item {
            DeviceToggleRow(
                title = stringResource(Res.string.enable_remote_control),
                checked = state.isRemoteControlEnabled,
                enabled = state.isDeviceEnabled && state.buttonEnabled,
                onCheckedChange = { enabled ->
                    viewModel.setRemoteControlStatus(enabled, deviceId)
                },
            )
        }

        item {
            Text(
                text = stringResource(Res.string.remote_control_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            HandshakeDelaySlider(
                delayMs = state.handshakeDelayMs,
                enabled = state.isDeviceEnabled && state.buttonEnabled,
                onDelayChanged = { delayMs ->
                    viewModel.setHandshakeDelay(delayMs, deviceId)
                },
            )
        }

        item {
            Text(
                text = stringResource(Res.string.handshake_delay_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (currentPlatform == KotlinPlatform.Android) {
            item {
                Text(
                    text = stringResource(Res.string.always_on_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                Text(
                    text = stringResource(Res.string.hint_if_issues_after_switching),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun HandshakeDelaySlider(
    delayMs: Long,
    enabled: Boolean,
    onDelayChanged: (Long) -> Unit,
) {
    // Local value while dragging; persisted only on release
    var sliderSeconds by remember(delayMs) { mutableStateOf((delayMs / 1000L).toFloat()) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.handshake_delay_title),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            val seconds = sliderSeconds.roundToInt()
            Text(
                text = if (seconds == 0) {
                    stringResource(Res.string.handshake_delay_off)
                } else {
                    stringResource(Res.string.handshake_delay_seconds, seconds)
                },
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Slider(
            value = sliderSeconds,
            onValueChange = { sliderSeconds = it },
            onValueChangeFinished = {
                onDelayChanged(sliderSeconds.roundToInt() * 1000L)
            },
            valueRange = 0f..10f,
            steps = 9,
            enabled = enabled,
        )
    }
}

@Composable
private fun DeviceToggleRow(
    title: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
        )
    }
}



