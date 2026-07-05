package com.sasch.cameragps.sharednew.ui.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.always_on_description
import cameragps.sharednew.generated.resources.enableConstantly
import cameragps.sharednew.generated.resources.enable_device
import cameragps.sharednew.generated.resources.enable_remote_control
import cameragps.sharednew.generated.resources.hint_if_issues_after_switching
import cameragps.sharednew.generated.resources.remote_control_hint
import com.sasch.cameragps.sharednew.util.KotlinPlatform
import com.sasch.cameragps.sharednew.util.currentPlatform
import org.jetbrains.compose.resources.stringResource

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
                    viewModel.setDeviceEnabled(enabled, deviceId)
                    onDeviceEnabledChanged?.invoke(enabled)
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



