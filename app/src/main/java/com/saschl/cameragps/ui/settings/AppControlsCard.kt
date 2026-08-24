package com.saschl.cameragps.ui.settings

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.app_controls
import cameragps.sharednew.generated.resources.enable_app
import cameragps.sharednew.generated.resources.enable_app_description
import cameragps.sharednew.generated.resources.enable_auto_start
import cameragps.sharednew.generated.resources.enable_auto_start_description
import cameragps.sharednew.generated.resources.haptic_feedback
import cameragps.sharednew.generated.resources.haptic_feedback_description
import cameragps.sharednew.generated.resources.reset_welcome
import cameragps.sharednew.generated.resources.will_show_welcome
import com.saschl.cameragps.service.LocationSenderService
import com.saschl.cameragps.utils.PreferencesManager
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun AppControlsCard(
    isAppEnabled: Boolean,
    onAppEnabledChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val willShowWelcomeText = stringResource(Res.string.will_show_welcome)

    SettingsCard(title = stringResource(Res.string.app_controls)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = stringResource(Res.string.enable_app),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = stringResource(Res.string.enable_app_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Switch(
                checked = isAppEnabled,
                onCheckedChange = { enabled ->
                    onAppEnabledChange(enabled)
                    context.stopService(
                        Intent(
                            context.applicationContext,
                            LocationSenderService::class.java
                        )
                    )
                }
            )
        }

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 8.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(0.6f),
            ) {
                Text(
                    text = stringResource(Res.string.enable_auto_start),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = stringResource(Res.string.enable_auto_start_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            var isAutoStartAfterRebootEnabled by remember {
                mutableStateOf(
                    PreferencesManager.getAutoStartAfterBootEnabled(context)
                )
            }
            Switch(
                checked = isAutoStartAfterRebootEnabled,
                onCheckedChange = { enabled ->
                    PreferencesManager.setAutoStartAfterBootEnabled(context, enabled)
                    isAutoStartAfterRebootEnabled = enabled
                }
            )
        }

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 8.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = stringResource(Res.string.haptic_feedback),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = stringResource(Res.string.haptic_feedback_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            var isHapticsEnabled by remember {
                mutableStateOf(PreferencesManager.isHapticsEnabled(context))
            }
            Switch(
                checked = isHapticsEnabled,
                onCheckedChange = { enabled ->
                    PreferencesManager.setHapticsEnabled(context, enabled)
                    isHapticsEnabled = enabled
                }
            )
        }

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 8.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(0.6f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Button(
                    onClick = {
                        val toast = Toast.makeText(
                            context, willShowWelcomeText,
                            Toast.LENGTH_SHORT
                        )
                        toast.show()
                        PreferencesManager.showFirstLaunch(context)
                        PreferencesManager.setPermissionsIgnored(context, false)
                    },
                ) {
                    Text(text = stringResource(Res.string.reset_welcome))
                }
            }
        }
    }
}

