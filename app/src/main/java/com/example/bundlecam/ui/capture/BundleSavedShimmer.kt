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
    var activeBundleId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(events) {
        events.filterIsInstance<CaptureEvent.BundleCommitted>().collect { event ->
            activeBundleId = event.bundleId
            delay(700)
            if (activeBundleId == event.bundleId) {
                activeBundleId = null
            }
        }
    }

    val bundleId = activeBundleId ?: return
    ShimmerPill(bundleId = bundleId, modifier = modifier)
}

@Composable
private fun ShimmerPill(bundleId: String, modifier: Modifier = Modifier) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(bundleId) {
        alpha.snapTo(0f)
        alpha.animateTo(1f, tween(durationMillis = 150))
        delay(400)
        alpha.animateTo(0f, tween(durationMillis = 150))
    }
    Surface(
        modifier = modifier.graphicsLayer { this.alpha = alpha.value },
        color = ShimmerGreen,
        contentColor = Color.White,
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 6.dp,
    ) {
        Text(
            text = "Bundle $bundleId saved",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}
