package com.saschl.cameragps.ui.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources._1_turn_on_your_camera
import cameragps.sharednew.generated.resources._2_go_to_camera_settings_and_enable_bluetooth_pairing_mode
import cameragps.sharednew.generated.resources._3_make_sure_the_camera_is_discoverable
import cameragps.sharednew.generated.resources.camera_pairing_required_title
import cameragps.sharednew.generated.resources.cancel
import cameragps.sharednew.generated.resources.continue_label
import cameragps.sharednew.generated.resources.done
import cameragps.sharednew.generated.resources.failed_to_pair_with_device
import cameragps.sharednew.generated.resources.once_your_camera_is_ready_tap_continue_to_start_pairing
import cameragps.sharednew.generated.resources.pairing_camera_title
import cameragps.sharednew.generated.resources.pairing_complete_title
import cameragps.sharednew.generated.resources.pairing_failed_hint_camera_pairing
import cameragps.sharednew.generated.resources.pairing_failed_hint_intro
import cameragps.sharednew.generated.resources.pairing_failed_hint_pairing_mode
import cameragps.sharednew.generated.resources.pairing_failed_hint_phone_pairing
import cameragps.sharednew.generated.resources.pairing_failed_title
import cameragps.sharednew.generated.resources.pairing_with_device_please_wait
import cameragps.sharednew.generated.resources.successfully_paired_with_device
import cameragps.sharednew.generated.resources.to_pair_with_your_camera_please
import cameragps.sharednew.generated.resources.try_again
import org.jetbrains.compose.resources.stringResource

@Composable
fun PairingConfirmationDialogWithLoading(
    deviceName: String,
    isPairing: Boolean,
    pairingResult: PairingResult?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = if (isPairing) { {} } else onDismiss, // Prevent dismissal during pairing
        title = {
            Text(
                text = when {
                    isPairing -> stringResource(Res.string.pairing_camera_title)
                    pairingResult == PairingResult.SUCCESS -> stringResource(Res.string.pairing_complete_title)
                    pairingResult == PairingResult.FAILED -> stringResource(Res.string.pairing_failed_title)
                    else -> stringResource(Res.string.camera_pairing_required_title)
                },
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column {
                when {
                    isPairing -> {
                        // Show loading state
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(end = 16.dp),
                                strokeWidth = 3.dp
                            )
                            Text(
                                text = stringResource(
                                    Res.string.pairing_with_device_please_wait,
                                    deviceName
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    pairingResult == PairingResult.SUCCESS -> {
                        // Show success state
                        Text(
                            text = stringResource(
                                Res.string.successfully_paired_with_device,
                                deviceName
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    pairingResult == PairingResult.FAILED -> {
                        // Show failure state with camera-side troubleshooting steps
                        Column(
                            modifier = Modifier.verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(
                                    Res.string.failed_to_pair_with_device,
                                    deviceName
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = stringResource(Res.string.pairing_failed_hint_intro),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = stringResource(Res.string.pairing_failed_hint_pairing_mode),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = stringResource(Res.string.pairing_failed_hint_camera_pairing),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = stringResource(Res.string.pairing_failed_hint_phone_pairing),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    else -> {
                        // Show initial instructions
                        Text(
                            text = stringResource(
                                Res.string.to_pair_with_your_camera_please,
                                deviceName
                            ),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = stringResource(Res.string._1_turn_on_your_camera),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = stringResource(Res.string._2_go_to_camera_settings_and_enable_bluetooth_pairing_mode),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = stringResource(Res.string._3_make_sure_the_camera_is_discoverable),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = stringResource(Res.string.once_your_camera_is_ready_tap_continue_to_start_pairing),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        },
        confirmButton = {
            when {
                isPairing -> {
                    // No buttons during pairing
                }
                pairingResult == PairingResult.SUCCESS -> {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(Res.string.done))
                    }
                }
                pairingResult == PairingResult.FAILED -> {
                    TextButton(onClick = onRetry) {
                        Text(stringResource(Res.string.try_again))
                    }
                }
                else -> {
                    TextButton(onClick = onConfirm) {
                        Text(stringResource(Res.string.continue_label))
                    }
                }
            }
        },
        dismissButton = {
            if (!isPairing && pairingResult != PairingResult.SUCCESS) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(Res.string.cancel))
                }
            }
        }
    )
}
