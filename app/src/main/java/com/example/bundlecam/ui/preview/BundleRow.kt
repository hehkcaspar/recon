package com.example.bundlecam.ui.preview

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.ViewStream
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import android.net.Uri
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.bundlecam.data.storage.BundleModality
import com.example.bundlecam.data.storage.CompletedBundle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val SWIPE_TRIGGER_FRACTION = 0.4f
private const val SWIPE_VELOCITY_PX_PER_S = 800f
private const val COUNTDOWN_TICK_MS = 200L
private val ThumbSize = 40.dp
private val ThumbGap = 4.dp
// Shared min-height so the pending-delete row and the normal row measure identically.
// Normal rows are content-driven by the two-line text column (titleMedium 24sp + 2dp +
// bodySmall 16sp = ~42dp) plus 24dp vertical padding, landing around 66dp; pending rows
// don't have the subtitle and would otherwise measure 2dp shorter, causing neighbours
// to shift when entering/leaving the pending state.
private val BundleRowMinHeight = 68.dp

private fun Modifier.bundleRowLayout(): Modifier =
    this.fillMaxWidth()
        .heightIn(min = BundleRowMinHeight)
        .padding(horizontal = 16.dp, vertical = 12.dp)
// Reserve the leading area for 3 slots so rows stay vertically aligned regardless of
// how many photos a given bundle has (stitched-only bundles only contribute 1 thumb).
private val ThumbStripWidth = ThumbSize * 3 + ThumbGap * 2

@Composable
fun BundleRow(
    bundle: CompletedBundle,
    thumbnails: Map<Uri, ImageBitmap>,
    pendingDelete: PendingDelete?,
    onRequestThumbnail: (Uri) -> Unit,
    onRequestDelete: () -> Unit,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(bundle.id) {
        bundle.thumbnailUris.forEach { uri ->
            if (!thumbnails.containsKey(uri)) onRequestThumbnail(uri)
        }
    }

    if (pendingDelete != null) {
        PendingDeleteRow(
            bundle = bundle,
            thumbnails = thumbnails,
            expiresAtMillis = pendingDelete.expiresAtMillis,
            onUndo = onUndo,
            modifier = modifier,
        )
        return
    }

    SwipeableBundleRow(
        bundle = bundle,
        thumbnails = thumbnails,
        onRequestDelete = onRequestDelete,
        modifier = modifier,
    )
}

@Composable
private fun SwipeableBundleRow(
    bundle: CompletedBundle,
    thumbnails: Map<Uri, ImageBitmap>,
    onRequestDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val coroutineScope = rememberCoroutineScope()
    // Two backing stores: `offsetX` is the live drag position (cheap plain state so we
    // don't launch a coroutine per touch delta), `animatable` drives the release
    // animation (snap-back on cancel, slide-out before firing the dialog). They're
    // synced in onDragStart so the animation picks up from the current finger position.
    var offsetX by remember(bundle.id) { mutableFloatStateOf(0f) }
    val animatable = remember(bundle.id) { Animatable(0f) }
    val velocityTracker = remember(bundle.id) { VelocityTracker() }
    var rowWidthPx by remember { mutableLongStateOf(0L) }
    var triggeredAbove by remember(bundle.id) { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer),
    ) {
        val progress = if (rowWidthPx > 0) {
            (-offsetX / rowWidthPx.toFloat()).coerceIn(0f, 1f)
        } else 0f
        val hintColor = MaterialTheme.colorScheme.onErrorContainer
        val hintAlpha = (progress * 2f).coerceAtMost(1f)
        if (hintAlpha > 0.01f) {
            // Compact row aligned to center-end of the parent Box so vertical centering
            // is guaranteed regardless of the Surface's measured height.
            Row(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Delete",
                    color = hintColor.copy(alpha = hintAlpha),
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = null,
                    tint = hintColor.copy(alpha = hintAlpha),
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = offsetX }
                .pointerInput(bundle.id) {
                    rowWidthPx = size.width.toLong()
                    val triggerPx = size.width * SWIPE_TRIGGER_FRACTION
                    detectHorizontalDragGestures(
                        onDragStart = {
                            triggeredAbove = false
                            velocityTracker.resetTracking()
                        },
                        onDragEnd = {
                            val velocity = velocityTracker.calculateVelocity().x
                            val shouldTrigger =
                                abs(offsetX) >= triggerPx || velocity <= -SWIPE_VELOCITY_PX_PER_S
                            coroutineScope.launch {
                                animatable.snapTo(offsetX)
                                if (shouldTrigger) {
                                    animatable.animateTo(-triggerPx)
                                    offsetX = animatable.value
                                    onRequestDelete()
                                    animatable.snapTo(0f)
                                    offsetX = 0f
                                } else {
                                    animatable.animateTo(0f) { offsetX = value }
                                    offsetX = 0f
                                }
                            }
                        },
                        onDragCancel = {
                            coroutineScope.launch {
                                animatable.snapTo(offsetX)
                                animatable.animateTo(0f) { offsetX = value }
                                offsetX = 0f
                            }
                        },
                        onHorizontalDrag = { change, delta ->
                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                            val next = (offsetX + delta).coerceAtMost(0f)
                            offsetX = next
                            val aboveNow = -next >= triggerPx
                            // Haptic on both crossings so dragging back below the line
                            // gives the same "you're in cancel territory" confirmation
                            // that the initial arming already signals.
                            if (aboveNow != triggeredAbove) {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                            triggeredAbove = aboveNow
                            if (next < 0f) change.consume()
                        },
                    )
                },
        ) {
            BundleRowContent(bundle = bundle, thumbnails = thumbnails)
        }
    }
}

@Composable
private fun BundleRowContent(
    bundle: CompletedBundle,
    thumbnails: Map<Uri, ImageBitmap>,
) {
    Row(
        modifier = Modifier.bundleRowLayout(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ThumbnailStrip(uris = bundle.thumbnailUris, thumbnails = thumbnails)

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = bundle.id,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.padding(top = 2.dp))
            val photoLabel = when (bundle.photoCount) {
                0 -> "stitch only"
                1 -> "1 photo"
                else -> "${bundle.photoCount} photos"
            }
            Text(
                text = photoLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.width(8.dp))

        ModalityIcons(modalities = bundle.modalities)
    }
}

@Composable
private fun ThumbnailStrip(
    uris: List<Uri>,
    thumbnails: Map<Uri, ImageBitmap>,
) {
    Row(
        modifier = Modifier.width(ThumbStripWidth),
        horizontalArrangement = Arrangement.spacedBy(ThumbGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        uris.take(3).forEach { uri ->
            Box(
                modifier = Modifier
                    .size(ThumbSize)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                val bitmap = thumbnails[uri]
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Image,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ModalityIcons(modalities: List<BundleModality>) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        modalities.forEach { modality ->
            val (icon, desc) = when (modality) {
                BundleModality.Stitch -> Icons.Outlined.ViewStream to "Vertical stitched image"
                BundleModality.Subfolder -> Icons.Outlined.PhotoLibrary to "Photos subfolder"
            }
            Icon(
                imageVector = icon,
                contentDescription = desc,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun PendingDeleteRow(
    bundle: CompletedBundle,
    thumbnails: Map<Uri, ImageBitmap>,
    expiresAtMillis: Long,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var nowMs by remember(expiresAtMillis) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(expiresAtMillis) {
        while (true) {
            nowMs = System.currentTimeMillis()
            if (expiresAtMillis - nowMs <= 0L) break
            delay(COUNTDOWN_TICK_MS)
        }
    }
    // 200ms ticks update `nowMs` but the displayed second only changes ~5× slower;
    // derivedStateOf gates recomposition so the row only redraws on whole-second boundaries.
    val secondsLeft by remember(expiresAtMillis) {
        derivedStateOf { ((expiresAtMillis - nowMs) / 1000L).coerceAtLeast(0L).toInt() + 1 }
    }

    Row(
        modifier = modifier.bundleRowLayout(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ThumbnailStrip(uris = bundle.thumbnailUris, thumbnails = thumbnails)

        Spacer(Modifier.width(12.dp))

        Text(
            text = "Deleting in ${secondsLeft}s",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        // Opt out of the 48dp minimum touch target for this button so the pending row's
        // measured height matches a normal row (thumbnail-driven 40dp). Without this,
        // the TextButton inflates the Row to ~72dp and pushes neighbouring rows down.
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
            TextButton(onClick = onUndo) { Text("Undo") }
        }
    }
}
