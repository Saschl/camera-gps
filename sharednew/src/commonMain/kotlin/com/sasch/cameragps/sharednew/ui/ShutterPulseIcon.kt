package com.sasch.cameragps.sharednew.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter

/**
 * Shutter-button icon that pulses while a shutter sequence is running,
 * giving visual feedback from half-press until the camera is ready again.
 */
@Composable
fun ShutterPulseIcon(
    isActive: Boolean,
    painter: Painter,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    if (!isActive) {
        Icon(painter, contentDescription, modifier)
        return
    }

    val infiniteTransition = rememberInfiniteTransition(label = "shutterPulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 450, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "shutterPulseScale",
    )

    Icon(
        painter = painter,
        contentDescription = contentDescription,
        modifier = modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
    )
}
