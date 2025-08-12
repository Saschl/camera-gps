/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.saschl.cameragps.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.MultiplePermissionsState
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import com.saschl.cameragps.R

/**
 * The PermissionBox uses a [Box] to show a simple permission request UI when the provided [permission]
 * is revoked or the provided [onGranted] content if the permission is granted.
 *
 * This composable follows the permission request flow but for a complete example check the samples
 * under privacy/permissions
 */
@Composable
fun PermissionBox(
    modifier: Modifier = Modifier,
    permission: String,
    description: String? = null,
    contentAlignment: Alignment = Alignment.TopStart,
    onGranted: @Composable BoxScope.() -> Unit,
) {
    PermissionBox(
        modifier,
        permissions = listOf(permission),
        requiredPermissions = listOf(permission),
        description,
        contentAlignment,
    ) { onGranted() }
}

/**
 * A variation of [PermissionBox] that takes a list of permissions and only calls [onGranted] when
 * all the [requiredPermissions] are granted.
 *
 * By default it assumes that all [permissions] are required.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PermissionBox(
    modifier: Modifier = Modifier,
    permissions: List<String>,
    requiredPermissions: List<String> = permissions,
    description: String? = null,
    contentAlignment: Alignment = Alignment.TopStart,
    onGranted: @Composable BoxScope.(List<String>) -> Unit,
) {
    val context = LocalContext.current
    var errorText by remember {
        mutableStateOf("")
    }

    val permissionState = rememberMultiplePermissionsState(permissions = permissions) { map ->
        val rejectedPermissions = map.filterValues { !it }.keys
        errorText = if (rejectedPermissions.none { it in requiredPermissions }) {
            ""
        } else {
            context.getString(
                R.string.permissions_required_app_error,
                rejectedPermissions.joinToString()
            )
        }
    }
    val allRequiredPermissionsGranted =
        permissionState.revokedPermissions.none { it.permission in requiredPermissions }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(modifier),
        contentAlignment = if (allRequiredPermissionsGranted) {
            contentAlignment
        } else {
            Alignment.Center
        },
    ) {
        if (allRequiredPermissionsGranted) {
            onGranted(
                permissionState.permissions
                    .filter { it.status.isGranted }
                    .map { it.permission },
            )
        } else {
            PermissionScreen(
                permissionState,
                description,
                errorText,
            )

         /*   FloatingActionButton(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                onClick = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        data = "package:${context.packageName}".toUri()
                    }
                    context.startActivity(intent)
                },
            ) {
                Icon(
                    imageVector = Icons.Rounded.Settings,
                    contentDescription = stringResource(R.string.app_settings)
                )
            }*/
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun PermissionScreen(
    state: MultiplePermissionsState,
    description: String?,
    errorText: String,
) {
    var showRationale by remember(state) {
        mutableStateOf(false)
    }

    val permissions = remember(state.revokedPermissions) {
        state.revokedPermissions.joinToString("\n") {
            " - " + it.permission.removePrefix("android.permission.")
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.sample_requires_permissions),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(16.dp),
        )
        Text(
            text = permissions,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
        )
        if (description != null) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }
        Button(
            onClick = {
                if (state.shouldShowRationale) {
                    showRationale = true
                } else {
                    state.launchMultiplePermissionRequest()
                }
            },
        ) {
            Text(text = stringResource(R.string.grant_permissions))
        }
        if (errorText.isNotBlank()) {
            Text(
                text = errorText,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
    if (showRationale) {
        AlertDialog(
            onDismissRequest = {
                showRationale = false
            },
            title = {
                Text(text = stringResource(R.string.permissions_required_title))
            },
            text = {
                Text(text = stringResource(R.string.permissions_required_message, permissions))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRationale = false
                        state.launchMultiplePermissionRequest()
                    },
                ) {
                    Text(stringResource(R.string.continue_button))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showRationale = false
                    },
                ) {
                    Text(stringResource(R.string.dismiss_button))
                }
            },
        )
    }
}

/**
 * Enhanced PermissionBox that properly handles background location permission according to Android guidelines.
 * For Android 10+ (API 29+), background location must be requested separately from foreground location.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun EnhancedLocationPermissionBox(
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.TopStart,
    onAllPermissionsGranted: @Composable BoxScope.() -> Unit,
) {
    val context = LocalContext.current
    var errorText by remember { mutableStateOf("") }

    // Foreground location permissions
    val foregroundLocationPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.POST_NOTIFICATIONS
        )
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
    } else {
        listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH,
        )
    }

    val foregroundPermissionState = rememberMultiplePermissionsState(
        permissions = foregroundLocationPermissions
    ) { map ->
        val rejectedPermissions = map.filterValues { !it }.keys
        errorText = if (rejectedPermissions.isNotEmpty()) {
            context.getString(
                R.string.permissions_required_app_error,
                rejectedPermissions.joinToString()
            )
        } else {
            ""
        }
    }

    // Background location permission (separate for Android 10+)
    val backgroundLocationPermission = rememberPermissionState(
        permission = Manifest.permission.ACCESS_BACKGROUND_LOCATION
    ) { granted ->
        errorText = if (!granted) {
            context.getString(R.string.background_location_required_error)
        } else {
            ""
        }
    }

    val allForegroundGranted = foregroundPermissionState.allPermissionsGranted
    val backgroundGranted = backgroundLocationPermission.status.isGranted
    val allPermissionsGranted = allForegroundGranted && backgroundGranted

    Scaffold { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .then(modifier),
            contentAlignment = if (allPermissionsGranted) {
                contentAlignment
            } else {
                Alignment.Center
            },
        ) {
            if (allPermissionsGranted) {
                onAllPermissionsGranted()
            } else {
                EnhancedPermissionScreen(
                    foregroundPermissionState = foregroundPermissionState,
                    backgroundLocationPermission = backgroundLocationPermission,
                    allForegroundGranted = allForegroundGranted,
                    errorText = errorText
                )

                /*         FloatingActionButton(
                             modifier = Modifier
                                 .align(Alignment.BottomEnd)
                                 .padding(16.dp),
                             onClick = {
                                 val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                     flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                     data = "package:${context.packageName}".toUri()
                                 }
                                 context.startActivity(intent)
                             },
                         ) {
                             Icon(imageVector = Icons.Rounded.Settings, contentDescription = stringResource(R.string.app_settings))
                         }*/
            }
        }
    }

}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun EnhancedPermissionScreen(
    foregroundPermissionState: MultiplePermissionsState,
    backgroundLocationPermission: PermissionState,
    allForegroundGranted: Boolean,
    errorText: String
) {
    var showForegroundRationale by remember { mutableStateOf(false) }
    var showBackgroundRationale by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .animateContentSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.camera_gps_permissions),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 16.dp),
        )

        // Step 1: Foreground Location Permissions
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (allForegroundGranted)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.step1_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.step1_desc),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(12.dp))

                if (!allForegroundGranted) {
                    val revokedPermissions =
                        foregroundPermissionState.revokedPermissions.map { it ->
                            stringResource(getPermissionDescription(it.permission))
                        }
                            .joinToString("\n") { " - $it" }

                    Text(
                        text = stringResource(R.string.step1_missing) + "\n" + revokedPermissions,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    Button(
                        onClick = {
                            if (foregroundPermissionState.shouldShowRationale) {
                                showForegroundRationale = true
                            } else {
                                foregroundPermissionState.launchMultiplePermissionRequest()
                            }
                        },
                    ) {
                        Text(text = stringResource(R.string.step1_grant))
                    }
                } else {
                    Text(
                        text = stringResource(R.string.step1_granted),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Step 2: Background Location Permission (only show if foreground is granted)
        if (allForegroundGranted) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (backgroundLocationPermission.status.isGranted)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.step2_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.step2_desc),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    if (!backgroundLocationPermission.status.isGranted) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            Text(
                                text = stringResource(R.string.step2_android10_warning),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.padding(bottom = 12.dp)
                            )
                        }

                        Button(
                            onClick = {
                                if (backgroundLocationPermission.status.shouldShowRationale) {
                                    showBackgroundRationale = true
                                } else {
                                    backgroundLocationPermission.launchPermissionRequest()
                                }
                            },
                        ) {
                            Text(text = stringResource(R.string.step2_grant))
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.step2_granted),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        if (errorText.isNotBlank()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = errorText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    // Rationale dialogs
    if (showForegroundRationale) {
        AlertDialog(
            onDismissRequest = { showForegroundRationale = false },
            title = { Text(stringResource(R.string.location_bluetooth_access_title)) },
            text = {
                Column {
                    Text(text = stringResource(R.string.location_bluetooth_access_message))
                    Text(text = stringResource(R.string.location_bluetooth_benefit_1))
                    Text(text = stringResource(R.string.location_bluetooth_benefit_2))
                    Text(text = stringResource(R.string.location_bluetooth_benefit_3))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showForegroundRationale = false
                        foregroundPermissionState.launchMultiplePermissionRequest()
                    }
                ) {
                    Text(stringResource(R.string.grant_permissions_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { showForegroundRationale = false }) {
                    Text(stringResource(R.string.cancel_button))
                }
            }
        )
    }

    if (showBackgroundRationale) {
        AlertDialog(
            onDismissRequest = { showBackgroundRationale = false },
            title = { Text(stringResource(R.string.background_location_access_title)) },
            text = {
                Column {
                    Text(text = stringResource(R.string.background_location_access_message))
                    Text(text = stringResource(R.string.background_location_benefit_1))
                    Text(text = stringResource(R.string.background_location_benefit_2))
                    Text(text = stringResource(R.string.background_location_benefit_3))
                    Text(fontWeight = FontWeight.Bold , text = stringResource(R.string.background_location_instruction))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showBackgroundRationale = false
                        backgroundLocationPermission.launchPermissionRequest()
                    }
                ) {
                    Text(stringResource(R.string.continue_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { showBackgroundRationale = false }) {
                    Text(stringResource(R.string.cancel_button))
                }
            }
        )
    }

}

/**
 * Gets a user-friendly description for what the permission is used for
 */
fun getPermissionDescription(permission: String): Int {
    //val context = LocalContext.current
    return when (permission) {
        Manifest.permission.ACCESS_FINE_LOCATION -> R.string.permission_gps
        Manifest.permission.ACCESS_BACKGROUND_LOCATION -> R.string.permission_background_gps
        Manifest.permission.BLUETOOTH_CONNECT -> R.string.permission_bluetooth
        Manifest.permission.POST_NOTIFICATIONS -> R.string.permission_notifications
        else -> -1
    }
}
