package com.saschl.cameragps.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.sentry_consent_allow
import cameragps.sharednew.generated.resources.sentry_consent_decline
import cameragps.sharednew.generated.resources.sentry_consent_dont_show
import cameragps.sharednew.generated.resources.sentry_consent_message
import cameragps.sharednew.generated.resources.sentry_consent_title
import com.saschl.cameragps.utils.CrashReporting
import com.saschl.cameragps.utils.PreferencesManager
import org.jetbrains.compose.resources.stringResource
import timber.log.Timber

@Composable
fun SentryConsentDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(Res.string.sentry_consent_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Text(
                text = stringResource(Res.string.sentry_consent_message),
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TextButton(
                    onClick = {
                        PreferencesManager.setSentryEnabled(context, true)
                        PreferencesManager.setSentryConsentDialogDismissed(context, true)
                        CrashReporting.init(context)
                        Timber.i("User accepted Sentry error reporting, initializing Sentry")
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(Res.string.sentry_consent_allow),
                        fontWeight = FontWeight.Medium
                    )
                }

                TextButton(
                    onClick = {
                        PreferencesManager.setSentryEnabled(context, false)
                        PreferencesManager.setSentryConsentDialogDismissed(context, true)
                        Timber.i("User declined Sentry error reporting")
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(Res.string.sentry_consent_decline),
                        fontWeight = FontWeight.Medium
                    )
                }

                TextButton(
                    onClick = {
                        PreferencesManager.setSentryEnabled(context, false)
                        PreferencesManager.setSentryConsentDialogDismissed(context, true)
                        Timber.i("User chose not to show Sentry consent dialog again")
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(Res.string.sentry_consent_dont_show))
                }
            }
        }
    )
}

