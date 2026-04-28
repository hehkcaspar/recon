package com.example.bundlecam.ui.preview

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.ViewStream
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
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
    selected: Boolean,
    selectionMode: Boolean,
    onRequestThumbnail: (Uri) -> Unit,
    onRequestDelete: () -> Unit,
    onUndo: () -> Unit,
    onToggleSelection: () -> Unit,
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
        selected = selected,
        selectionMode = selectionMode,
        onRequestDelete = onRequestDelete,
        onToggleSelection = onToggleSelection,
        modifier = modifier,
    )
}

@Composable
private fun SwipeableBundleRow(
    bundle: CompletedBundle,
    thumbnails: Map<Uri, ImageBitmap>,
    selected: Boolean,
    selectionMode: Boolean,
    onRequestDelete: () -> Unit,
    onToggleSelection: () -> Unit,
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

    val deleteRevealColor = MaterialTheme.colorScheme.errorContainer
    val selectRevealColor = MaterialTheme.colorScheme.primaryContainer
    val containerColor = when {
        offsetX < 0f -> deleteRevealColor
        offsetX > 0f -> selectRevealColor
        else -> Color.Transparent
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(containerColor),
    ) {
        val widthF = rowWidthPx.toFloat()
        val deleteProgress = if (widthF > 0f && offsetX < 0f) {
            (-offsetX / widthF).coerceIn(0f, 1f)
        } else 0f
        val selectProgress = if (widthF > 0f && offsetX > 0f) {
            (offsetX / widthF).coerceIn(0f, 1f)
        } else 0f

        if (deleteProgress > 0f) {
            DeleteHint(
                color = MaterialTheme.colorScheme.onErrorContainer,
                progress = deleteProgress,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 16.dp),
            )
        }
        if (selectProgress > 0f) {
            SelectHint(
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                progress = selectProgress,
                alreadySelected = selected,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 16.dp),
            )
        }

        // Selected rows get a subtle tone shift via `surfaceContainerHighest`. Sticking
        // with M3's tonal palette (rather than ad-hoc primaryContainer alpha) keeps the
        // selected state distinguishable in both light + dark themes without code paths
        // for color-mode handling.
        val rowColor =
            if (selected) MaterialTheme.colorScheme.surfaceContainerHighest
            else MaterialTheme.colorScheme.surface

        Surface(
            color = rowColor,
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = offsetX }
                .pointerInput(bundle.id) {
                    detectTapGestures(
                        onLongPress = { onToggleSelection() },
                        // Tap is a no-op outside selection mode (matches existing behavior;
                        // bundles don't open into a detail screen yet). In selection mode,
                        // tap is the primary toggle so users don't have to swipe every row.
                        onTap = { if (selectionMode) onToggleSelection() },
                    )
                }
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
                            val absVelocity = abs(velocity)
                            val shouldTrigger =
                                abs(offsetX) >= triggerPx || absVelocity >= SWIPE_VELOCITY_PX_PER_S
                            val direction = when {
                                offsetX < 0f -> -1
                                offsetX > 0f -> 1
                                else -> 0
                            }
                            coroutineScope.launch {
                                animatable.snapTo(offsetX)
                                if (shouldTrigger && direction != 0) {
                                    animatable.animateTo(direction * triggerPx)
                                    offsetX = animatable.value
                                    when (direction) {
                                        -1 -> onRequestDelete()
                                        1 -> onToggleSelection()
                                    }
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
                            // Signed offset — left fires delete, right fires selection toggle.
                            val next = offsetX + delta
                            offsetX = next
                            val aboveNow = abs(next) >= triggerPx
                            // Haptic on both crossings so dragging back below the line
                            // gives the same "you're in cancel territory" confirmation
                            // that the initial arming already signals.
                            if (aboveNow != triggeredAbove) {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                            triggeredAbove = aboveNow
                            if (next != 0f) change.consume()
                        },
                    )
                },
        ) {
            BundleRowContent(
                bundle = bundle,
                thumbnails = thumbnails,
                selected = selected,
            )
        }
    }
}

@Composable
private fun DeleteHint(
    color: Color,
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val alpha = (progress * 2f).coerceAtMost(1f)
    if (alpha < 0.01f) return
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "Delete",
            color = color.copy(alpha = alpha),
            style = MaterialTheme.typography.labelLarge,
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = Icons.Filled.Delete,
            contentDescription = null,
            tint = color.copy(alpha = alpha),
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun SelectHint(
    color: Color,
    progress: Float,
    alreadySelected: Boolean,
    modifier: Modifier = Modifier,
) {
    val alpha = (progress * 2f).coerceAtMost(1f)
    if (alpha < 0.01f) return
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = null,
            tint = color.copy(alpha = alpha),
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            // Toggle wording mirrors the actual semantic — swiping right on an already-
            // selected row deselects it. Helps the user form the right mental model the
            // first time they discover the gesture.
            text = if (alreadySelected) "Deselect" else "Select",
            color = color.copy(alpha = alpha),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun BundleRowContent(
    bundle: CompletedBundle,
    thumbnails: Map<Uri, ImageBitmap>,
    selected: Boolean = false,
) {
    Row(
        modifier = Modifier.bundleRowLayout(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ThumbnailStrip(
            uris = bundle.thumbnailUris,
            thumbnails = thumbnails,
            checkedOverlay = selected,
        )

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
            Text(
                text = subtitleFor(bundle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.width(8.dp))

        ModalityIcons(bundle = bundle)
    }
}

@Composable
private fun ThumbnailStrip(
    uris: List<Uri>,
    thumbnails: Map<Uri, ImageBitmap>,
    checkedOverlay: Boolean = false,
) {
    Row(
        modifier = Modifier.width(ThumbStripWidth),
        horizontalArrangement = Arrangement.spacedBy(ThumbGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        uris.take(3).forEachIndexed { index, uri ->
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
                if (checkedOverlay && index == 0) {
                    // Filled check disc in the bottom-right corner of the leading thumb
                    // — a single targeted glance-affordance that survives the row's
                    // subtle tone-shift selected state without competing with the existing
                    // modality-icons cluster on the trailing edge.
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(2.dp)
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = "Selected",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Compose the per-modality subtitle for a bundle row. Preserves the pre-multimodal
 * "N photos" / "stitch only" wording when the bundle is photo-only; adds comma-
 * separated entries for videos and voice notes when present. Zero-photo bundles that
 * only contain video/voice read "M videos" or "K voice notes" rather than "stitch only",
 * because "stitch only" now specifically means "no raw content at all".
 */
private fun subtitleFor(bundle: CompletedBundle): String {
    val parts = buildList {
        if (bundle.photoCount > 0) add(pluralize(bundle.photoCount, "photo", "photos"))
        if (bundle.videoCount > 0) add(pluralize(bundle.videoCount, "video", "videos"))
        if (bundle.voiceCount > 0) add(pluralize(bundle.voiceCount, "voice note", "voice notes"))
    }
    return when {
        parts.isNotEmpty() -> parts.joinToString(", ")
        bundle.stitchUri != null -> "stitch only"
        else -> "empty"
    }
}

private fun pluralize(count: Int, singular: String, plural: String): String =
    "$count ${if (count == 1) singular else plural}"

@Composable
private fun ModalityIcons(bundle: CompletedBundle) {
    // Render one icon per modality the bundle actually contains. We intentionally don't
    // rely on BundleModality.Subfolder alone (that enum stayed at {Subfolder, Stitch}
    // so downstream code doesn't have to change), but split it into photo / video /
    // voice icons based on the per-modality count fields so the row gives the user an
    // at-a-glance read of what's inside.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (bundle.photoCount > 0) {
            Icon(
                imageVector = Icons.Outlined.PhotoLibrary,
                contentDescription = "Photos subfolder",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        if (bundle.videoCount > 0) {
            Icon(
                imageVector = Icons.Outlined.VideoLibrary,
                contentDescription = "Videos subfolder",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        if (bundle.voiceCount > 0) {
            Icon(
                imageVector = Icons.Outlined.Mic,
                contentDescription = "Voice notes subfolder",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        if (bundle.stitchUri != null) {
            Icon(
                imageVector = Icons.Outlined.ViewStream,
                contentDescription = "Vertical stitched image",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
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

@Composable
fun ProcessingBundleRow(
    bundleId: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.bundleRowLayout(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Reserve the same leading strip as completed rows so bundle IDs stay aligned
        // when a row transitions from processing to completed.
        Box(
            modifier = Modifier.width(ThumbStripWidth),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .size(ThumbSize)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = bundleId,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.padding(top = 2.dp))
            Text(
                text = "Processing…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
