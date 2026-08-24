package com.sasch.cameragps.sharednew

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.app_controls
import cameragps.sharednew.generated.resources.arrow_back_24px
import cameragps.sharednew.generated.resources.auto_scan
import cameragps.sharednew.generated.resources.auto_scan_description
import cameragps.sharednew.generated.resources.back
import cameragps.sharednew.generated.resources.cancel_button
import cameragps.sharednew.generated.resources.enable_app
import cameragps.sharednew.generated.resources.enable_app_description
import cameragps.sharednew.generated.resources.haptic_feedback
import cameragps.sharednew.generated.resources.haptic_feedback_description
import cameragps.sharednew.generated.resources.log_level
import cameragps.sharednew.generated.resources.log_settings
import cameragps.sharednew.generated.resources.settings
import cameragps.sharednew.generated.resources.tip_jar
import cameragps.sharednew.generated.resources.tip_jar_description
import cameragps.sharednew.generated.resources.tip_jar_dismiss
import cameragps.sharednew.generated.resources.tip_jar_error_prefix
import cameragps.sharednew.generated.resources.tip_jar_loading
import cameragps.sharednew.generated.resources.tip_jar_thank_you
import cameragps.sharednew.generated.resources.tip_jar_unavailable
import com.diamondedge.logging.LogLevel
import com.sasch.cameragps.sharednew.ui.settings.SharedSettingsCard
import com.sasch.cameragps.sharednew.ui.settings.SharedSettingsScreen
import com.sasch.cameragps.sharednew.ui.settings.SharedToggleRow
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import platform.Foundation.NSLog

private enum class IosLogLevel {
    OFF,
    ERROR,
    WARN,
    INFO,
    DEBUG,
}

@Composable
internal fun IosSettingsScreen(
    isAppEnabled: Boolean,
    autoScanEnabled: Boolean,
    hapticsEnabled: Boolean,
    scrollToTipJarOnOpen: Boolean = false,
    onBackClick: () -> Unit,
    onOpenHelp: () -> Unit,
    onAppEnabledChange: (Boolean) -> Unit,
    onAutoScanEnabledChange: (Boolean) -> Unit,
    onHapticsEnabledChange: (Boolean) -> Unit,
    onShowWelcomeAgain: () -> Unit,
    onChangeLogLevel: (LogLevel) -> Unit,
    onTipJarScrollConsumed: () -> Unit = {},
) {
    var selectedLogLevel by remember { mutableStateOf(LogLevel.valueOf(IosAppPreferences.getLogLevel())) }
    var debugTapCounter by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()

    LaunchedEffect(scrollToTipJarOnOpen) {
        if (!scrollToTipJarOnOpen) return@LaunchedEffect
        listState.animateScrollToItem(index = TIP_JAR_ITEM_INDEX, 1)
        onTipJarScrollConsumed()
    }

    SharedSettingsScreen(
        title = stringResource(Res.string.settings),
        onBackClick = onBackClick,
        onTitleClick = { debugTapCounter++ },
        navigationIcon = {
            Icon(
                painterResource(Res.drawable.arrow_back_24px),
                contentDescription = stringResource(Res.string.back)
            )
        },
    ) { paddingValues ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            item {
                SharedSettingsCard(title = stringResource(Res.string.app_controls)) {
                    SharedToggleRow(
                        title = stringResource(Res.string.enable_app),
                        description = stringResource(Res.string.enable_app_description),
                        checked = isAppEnabled,
                        onCheckedChange = onAppEnabledChange,
                    )
                    SharedToggleRow(
                        title = stringResource(Res.string.auto_scan),
                        description = stringResource(Res.string.auto_scan_description),
                        checked = autoScanEnabled,
                        onCheckedChange = onAutoScanEnabledChange,
                    )
                    SharedToggleRow(
                        title = stringResource(Res.string.haptic_feedback),
                        description = stringResource(Res.string.haptic_feedback_description),
                        checked = hapticsEnabled,
                        onCheckedChange = onHapticsEnabledChange,
                    )
                }
            }

            item {
                IosLogLevelPlaceholderCard(
                    selectedLevel = selectedLogLevel,
                    onLevelSelected = {
                        selectedLogLevel = it
                        IosAppPreferences.setLogLevel(it.name)
                        NSLog("selected log level: ${it.name}")
                        onChangeLogLevel(it)
                    },
                )
            }

            item {
                IosTipJarCard()
            }

            if (debugTapCounter >= 5) {
                item {
                    IosDebugCard()
                }
            }
        }
    }
}

private const val TIP_JAR_ITEM_INDEX = 2

@Composable
private fun IosDebugCard() {
    var queued by remember { mutableStateOf(false) }

    SharedSettingsCard(title = "Debug") {
        Text(
            text = "Internal debug actions",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = {
                IosAppPreferences.setForceDonationDialogOnNextAppStart(true)
                queued = true
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = "Show donation dialog on next app start")
        }

        if (queued) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Queued for next app start.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun IosLogLevelPlaceholderCard(
    selectedLevel: LogLevel,
    onLevelSelected: (LogLevel) -> Unit,
) {
    var showLogLevelDialog by remember { mutableStateOf(false) }

    SharedSettingsCard(title = stringResource(Res.string.log_settings)) {

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showLogLevelDialog = true },
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(Res.string.log_level),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = selectedLevel.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (showLogLevelDialog) {
            AlertDialog(
                onDismissRequest = { showLogLevelDialog = false },
                title = {
                    Text(
                        text = stringResource(Res.string.log_level),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        LogLevel.entries.forEach { level ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onLevelSelected(level)
                                        showLogLevelDialog = false
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = level == selectedLevel,
                                    onClick = {
                                        onLevelSelected(level)
                                        showLogLevelDialog = false
                                    },
                                )
                                Text(
                                    text = level.name,
                                    modifier = Modifier.padding(start = 8.dp),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showLogLevelDialog = false }) {
                        Text(stringResource(Res.string.cancel_button))
                    }
                },
            )
        }
    }
}

@Composable
private fun IosTipJarCard() {
    val products by IosTipJarController.products.collectAsState()
    val purchaseState by IosTipJarController.purchaseState.collectAsState()
    val isLoadingProducts by IosTipJarController.isLoadingProducts.collectAsState()

    LaunchedEffect(Unit) {
        if (products.isEmpty() && !isLoadingProducts) {
            IosTipJarController.fetchProducts()
        }
    }

    SharedSettingsCard(title = stringResource(Res.string.tip_jar)) {
        Text(
            text = stringResource(Res.string.tip_jar_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(12.dp))

        when {
            // ── Purchase succeeded ──────────────────────────────────────────
            purchaseState is TipPurchaseState.Success -> {
                Text(
                    text = stringResource(Res.string.tip_jar_thank_you),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                )
                TextButton(onClick = { IosTipJarController.resetPurchaseState() }) {
                    Text(stringResource(Res.string.tip_jar_dismiss))
                }
            }

            // ── Purchase error ──────────────────────────────────────────────
            purchaseState is TipPurchaseState.Error -> {
                val msg = (purchaseState as TipPurchaseState.Error).message
                Text(
                    text = "${stringResource(Res.string.tip_jar_error_prefix)} $msg",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = { IosTipJarController.resetPurchaseState() }) {
                    Text(stringResource(Res.string.tip_jar_dismiss))
                }
            }

            // ── Purchase in progress ────────────────────────────────────────
            purchaseState is TipPurchaseState.Loading -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(
                        text = stringResource(Res.string.tip_jar_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ── Fetching products ───────────────────────────────────────────
            isLoadingProducts -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(
                        text = stringResource(Res.string.tip_jar_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ── Products unavailable ────────────────────────────────────────
            products.isEmpty() || !IosTipJarController.canMakePurchases() -> {
                Text(
                    text = stringResource(Res.string.tip_jar_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ── Tip buttons ─────────────────────────────────────────────────
            else -> {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    products.forEach { product ->
                        OutlinedButton(
                            onClick = { IosTipJarController.purchase(product) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.primary,
                            ),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = product.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = product.formattedPrice,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
