package com.sasch.cameragps.sharednew.ui.help

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.guide_accuracy_description
import cameragps.sharednew.generated.resources.guide_accuracy_step_battery
import cameragps.sharednew.generated.resources.guide_accuracy_step_precise
import cameragps.sharednew.generated.resources.guide_accuracy_step_precise_path_android
import cameragps.sharednew.generated.resources.guide_accuracy_step_precise_path_ios
import cameragps.sharednew.generated.resources.guide_accuracy_title
import cameragps.sharednew.generated.resources.guide_intro
import cameragps.sharednew.generated.resources.guide_link_location_a6400
import cameragps.sharednew.generated.resources.guide_link_location_zve10
import cameragps.sharednew.generated.resources.guide_link_remote_a6400
import cameragps.sharednew.generated.resources.guide_location_link_description
import cameragps.sharednew.generated.resources.guide_location_link_step_enable
import cameragps.sharednew.generated.resources.guide_location_link_step_enable_path
import cameragps.sharednew.generated.resources.guide_location_link_step_remote_note
import cameragps.sharednew.generated.resources.guide_location_link_step_time
import cameragps.sharednew.generated.resources.guide_location_link_step_time_path
import cameragps.sharednew.generated.resources.guide_location_link_title
import cameragps.sharednew.generated.resources.guide_remote_description
import cameragps.sharednew.generated.resources.guide_remote_step_app
import cameragps.sharednew.generated.resources.guide_remote_step_camera
import cameragps.sharednew.generated.resources.guide_remote_step_camera_path
import cameragps.sharednew.generated.resources.guide_remote_title
import cameragps.sharednew.generated.resources.guide_repair_description
import cameragps.sharednew.generated.resources.guide_repair_step_camera
import cameragps.sharednew.generated.resources.guide_repair_step_camera_path
import cameragps.sharednew.generated.resources.guide_repair_step_pair_again
import cameragps.sharednew.generated.resources.guide_repair_step_phone
import cameragps.sharednew.generated.resources.guide_repair_step_phone_path_android
import cameragps.sharednew.generated.resources.guide_repair_step_phone_path_ios
import cameragps.sharednew.generated.resources.guide_repair_step_sony_app
import cameragps.sharednew.generated.resources.guide_repair_step_sony_app_path
import cameragps.sharednew.generated.resources.guide_repair_title
import com.sasch.cameragps.sharednew.util.KotlinPlatform
import com.sasch.cameragps.sharednew.util.currentPlatform
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private data class GuideStep(
    val text: StringResource,
    val menuPath: StringResource? = null,
)

private data class GuideSection(
    val title: StringResource,
    val description: StringResource,
    val steps: List<GuideStep>,
    val links: List<Pair<StringResource, String>> = emptyList(),
)

/**
 * Visual step-by-step guide for the most common issues, shared by both platforms.
 * Each platform wraps it in its own scaffold (see the Android `TroubleshootingScreen`
 * and `IosTroubleshootingScreen`).
 */
@Composable
fun TroubleshootingGuideContent(innerPadding: PaddingValues) {
    val isAndroid = currentPlatform == KotlinPlatform.Android
    val sections = listOf(
        GuideSection(
            title = Res.string.guide_location_link_title,
            description = Res.string.guide_location_link_description,
            steps = listOf(
                GuideStep(
                    Res.string.guide_location_link_step_enable,
                    Res.string.guide_location_link_step_enable_path
                ),
                GuideStep(
                    Res.string.guide_location_link_step_time,
                    Res.string.guide_location_link_step_time_path
                ),
                GuideStep(Res.string.guide_location_link_step_remote_note),
            ),
            links = listOf(
                Res.string.guide_link_location_a6400 to SonyDocLinks.LOCATION_LINK_MENU_A6400,
                Res.string.guide_link_location_zve10 to SonyDocLinks.LOCATION_LINK_MENU_ZVE10,
            ),
        ),
        GuideSection(
            title = Res.string.guide_repair_title,
            description = Res.string.guide_repair_description,
            steps = listOf(
                GuideStep(
                    Res.string.guide_repair_step_phone,
                    if (isAndroid) Res.string.guide_repair_step_phone_path_android
                    else Res.string.guide_repair_step_phone_path_ios
                ),
                GuideStep(
                    Res.string.guide_repair_step_camera,
                    Res.string.guide_repair_step_camera_path
                ),
                GuideStep(
                    Res.string.guide_repair_step_sony_app,
                    Res.string.guide_repair_step_sony_app_path
                ),
                GuideStep(Res.string.guide_repair_step_pair_again),
            ),
        ),
        GuideSection(
            title = Res.string.guide_remote_title,
            description = Res.string.guide_remote_description,
            steps = listOf(
                GuideStep(
                    Res.string.guide_remote_step_camera,
                    Res.string.guide_remote_step_camera_path
                ),
                GuideStep(Res.string.guide_remote_step_app),
            ),
            links = listOf(
                Res.string.guide_link_remote_a6400 to SonyDocLinks.BLUETOOTH_REMOTE_MENU_A6400,
            ),
        ),
        GuideSection(
            title = Res.string.guide_accuracy_title,
            description = Res.string.guide_accuracy_description,
            steps = buildList {
                add(
                    GuideStep(
                        Res.string.guide_accuracy_step_precise,
                        if (isAndroid) Res.string.guide_accuracy_step_precise_path_android
                        else Res.string.guide_accuracy_step_precise_path_ios
                    )
                )
                if (isAndroid) {
                    add(GuideStep(Res.string.guide_accuracy_step_battery))
                }
            },
        ),
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
    ) {
        item {
            Text(
                text = stringResource(Res.string.guide_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        itemsIndexed(sections) { index, section ->
            GuideSectionCard(number = index + 1, section = section)
        }
    }
}

@Composable
private fun GuideSectionCard(number: Int, section: GuideSection) {
    val uriHandler = LocalUriHandler.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = number.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(section.title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(section.description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            section.steps.forEachIndexed { index, step ->
                Spacer(modifier = Modifier.height(12.dp))
                GuideStepRow(number = index + 1, step = step)
            }
            if (section.links.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                section.links.forEach { (labelRes, url) ->
                    Text(
                        text = stringResource(labelRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier
                            .clickable { uriHandler.openUri(url) }
                            .padding(vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun GuideStepRow(number: Int, step: GuideStep) {
    Row {
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .size(20.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(step.text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            step.menuPath?.let { pathRes ->
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Text(
                        text = stringResource(pathRes),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}
