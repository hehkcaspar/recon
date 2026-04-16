package com.example.bundlecam.ui.capture

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance

private val ShimmerGreen = CaptureColors.CommitGreen

@Composable
fun BundleSavedShimmer(
    events: Flow<CaptureEvent>,
    modifier: Modifier = Modifier,
) {
    var activeIds by remember { mutableStateOf<List<String>?>(null) }

    LaunchedEffect(events) {
        events.filterIsInstance<CaptureEvent.BundlesCommitted>().collect { event ->
            if (event.bundleIds.isEmpty()) return@collect
            activeIds = event.bundleIds
            delay(900)
            if (activeIds == event.bundleIds) {
                activeIds = null
            }
        }
    }

    val ids = activeIds ?: return
    ShimmerPill(bundleIds = ids, modifier = modifier)
}

@Composable
private fun ShimmerPill(bundleIds: List<String>, modifier: Modifier = Modifier) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(bundleIds) {
        alpha.snapTo(0f)
        alpha.animateTo(1f, tween(durationMillis = 150))
        delay(600)
        alpha.animateTo(0f, tween(durationMillis = 150))
    }
    val label = when (bundleIds.size) {
        1 -> "Bundle ${bundleIds.first()} saved"
        else -> "${bundleIds.size} bundles saved (${bundleIds.first()}–${bundleIds.last()})"
    }
    Surface(
        modifier = modifier.graphicsLayer { this.alpha = alpha.value },
        color = ShimmerGreen,
        contentColor = Color.White,
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 6.dp,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}
