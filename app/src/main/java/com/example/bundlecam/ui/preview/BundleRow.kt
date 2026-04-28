package com.example.bundlecam.ui.preview

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.CheckCircle
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
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.bundlecam.data.storage.CompletedBundle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Width of the selection reveal zone — the row settles here when selected, exposing
// the green check affordance on the leading edge.
private val SwipeRevealWidth = 80.dp
// Fractions of revealWidth / row-width that count as a release-commit. Keeping the
// select midpoint at 0.5 of the reveal width means a half-pull is enough to commit;
// keeping the delete trigger at 0.4 of the row width matches the pre-existing delete
// gesture's hard reach so swipe-left muscle memory carries over.
private const val SWIPE_SELECT_FRACTION = 0.5f
private const val SWIPE_DELETE_FRACTION = 0.4f
private const val SWIPE_VELOCITY_PX_PER_S = 800f
private const val COUNTDOWN_TICK_MS = 200L

// M3 dynamic-color palettes can land anywhere — but "green = selected/confirm" is a
// universally-readable signal, so the reveal background is hardcoded rather than
// pulled from `colorScheme.primary`. Material green-800 reads cleanly on both light
// and dark themes.
private val SelectionGreen = Color(0xFF2E7D32)

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
    onRequestDelete: () -> Unit,
    onToggleSelection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val revealWidthPx = with(density) { SwipeRevealWidth.toPx() }

    // pointerInput is keyed only on bundle.id, so its drag-handler closures see stale
    // values for `selected` / callbacks if those props change without a key bump (which
    // would cancel an in-flight gesture). rememberUpdatedState gives us a State<T> the
    // closures re-read on each invocation.
    val currentSelected by rememberUpdatedState(selected)
    val currentOnToggleSelection by rememberUpdatedState(onToggleSelection)
    val currentOnRequestDelete by rememberUpdatedState(onRequestDelete)

    // offsetX is the live drag position — plain state so we don't launch a coroutine
    // per touch delta. animatable drives release / external-change animations and is
    // kept in sync via snapTo at the start of every animated transition.
    val initialOffset = if (selected) revealWidthPx else 0f
    var offsetX by remember(bundle.id) { mutableFloatStateOf(initialOffset) }
    val animatable = remember(bundle.id) { Animatable(initialOffset) }
    val velocityTracker = remember(bundle.id) { VelocityTracker() }
    var rowWidthPx by remember { mutableLongStateOf(0L) }
    var triggeredAbove by remember(bundle.id) { mutableStateOf(false) }
    // True while the row is mid-animation from an external selection change (e.g. the
    // contextual app bar's X click). Drag handlers consult this on drag-start to ignore
    // any finger motion that's actually a follow-through from the user's tap on X — a
    // common pattern that would otherwise trigger swipe-right and re-select the row.
    var animatingExternally by remember(bundle.id) { mutableStateOf(false) }

    // External selection changes (e.g. contextual app bar's clear-all) animate the row
    // back to its rest target, so the snap-out/in stays visually consistent with the
    // user's own swipe.
    LaunchedEffect(selected, revealWidthPx) {
        val target = if (selected) revealWidthPx else 0f
        if (offsetX != target) {
            animatingExternally = true
            try {
                animatable.snapTo(offsetX)
                animatable.animateTo(target) { offsetX = value }
            } finally {
                animatingExternally = false
            }
        }
    }

    val containerColor = when {
        offsetX < 0f -> MaterialTheme.colorScheme.errorContainer
        offsetX > 0f -> SelectionGreen
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

        if (deleteProgress > 0f) {
            DeleteHint(
                color = MaterialTheme.colorScheme.onErrorContainer,
                progress = deleteProgress,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 16.dp),
            )
        }

        // Selection reveal: 80dp wide tap target on the leading edge, vertically
        // centered, with a check-circle icon. Composed only when offsetX > 0 so it
        // doesn't intercept clicks on an unselected row. Clickable when the row is
        // at-rest selected — tapping fires the toggle (deselect). During mid-drag
        // (offsetX > 0 but selected still false) the click is disabled; the gesture
        // committer is the drag's release, not a tap.
        if (offsetX > 0f) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(SwipeRevealWidth)
                    .heightIn(min = BundleRowMinHeight)
                    .clickable(enabled = selected) { onToggleSelection() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = if (selected) "Deselect bundle" else null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
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
                    val deleteTriggerPx = -size.width * SWIPE_DELETE_FRACTION
                    val selectMidpointPx = revealWidthPx * SWIPE_SELECT_FRACTION
                    // Per-gesture suppression flag — set on drag-start when the row is
                    // mid-animation from an external selection change. Stays set for the
                    // remainder of the gesture so a follow-through finger motion right
                    // after the user tapped X doesn't drive offsetX or commit a toggle.
                    var dragSuppressed = false

                    detectHorizontalDragGestures(
                        onDragStart = {
                            dragSuppressed = animatingExternally
                            if (!dragSuppressed) {
                                triggeredAbove = false
                                velocityTracker.resetTracking()
                            }
                        },
                        onDragEnd = {
                            if (dragSuppressed) return@detectHorizontalDragGestures
                            val velocity = velocityTracker.calculateVelocity().x
                            val isFastRight = velocity >= SWIPE_VELOCITY_PX_PER_S
                            val isFastLeft = velocity <= -SWIPE_VELOCITY_PX_PER_S
                            coroutineScope.launch {
                                animatable.snapTo(offsetX)
                                when {
                                    // Selected → deselect: dragged left toward 0, or fast left flick.
                                    currentSelected &&
                                        (offsetX <= selectMidpointPx ||
                                            (isFastLeft && offsetX < revealWidthPx)) -> {
                                        animatable.animateTo(0f) { offsetX = value }
                                        currentOnToggleSelection()
                                    }
                                    // Unselected → select: dragged right past midpoint, or fast right flick.
                                    !currentSelected && offsetX > 0f &&
                                        (offsetX >= selectMidpointPx || isFastRight) -> {
                                        animatable.animateTo(revealWidthPx) { offsetX = value }
                                        currentOnToggleSelection()
                                    }
                                    // Unselected → delete: dragged left past delete threshold, or fast left flick.
                                    !currentSelected && offsetX < 0f &&
                                        (offsetX <= deleteTriggerPx || isFastLeft) -> {
                                        animatable.animateTo(deleteTriggerPx) { offsetX = value }
                                        currentOnRequestDelete()
                                        // pendingDeleteRow takes over on the next recomposition;
                                        // snap the gesture state to 0 in case the bundle reappears
                                        // (e.g. user undoes the delete) so the row shows at rest.
                                        animatable.snapTo(0f)
                                        offsetX = 0f
                                    }
                                    // Otherwise: spring back to current rest.
                                    else -> {
                                        val rest = if (currentSelected) revealWidthPx else 0f
                                        animatable.animateTo(rest) { offsetX = value }
                                    }
                                }
                            }
                        },
                        onDragCancel = {
                            if (dragSuppressed) return@detectHorizontalDragGestures
                            coroutineScope.launch {
                                val rest = if (currentSelected) revealWidthPx else 0f
                                animatable.snapTo(offsetX)
                                animatable.animateTo(rest) { offsetX = value }
                            }
                        },
                        onHorizontalDrag = { change, delta ->
                            if (dragSuppressed) {
                                // Claim the gesture so it doesn't propagate, but ignore the
                                // delta — the LaunchedEffect's animation owns offsetX while
                                // the row is animating to rest.
                                change.consume()
                                return@detectHorizontalDragGestures
                            }
                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                            // Clamp depends on starting state at drag-start (= currentSelected
                            // since no toggle fires until drag-end): selected rows can only
                            // travel between 0 and revealWidth (no delete from a selected row,
                            // no over-drag past reveal). Unselected rows can swipe right up to
                            // revealWidth (selection cap) or arbitrarily left (delete reveal).
                            val raw = offsetX + delta
                            val next = if (currentSelected) {
                                raw.coerceIn(0f, revealWidthPx)
                            } else {
                                raw.coerceAtMost(revealWidthPx)
                            }
                            offsetX = next
                            // Haptic on each crossing of "would-fire-on-release" so the user
                            // gets confirmation that their pull is committable.
                            val wouldFire = when {
                                currentSelected -> next <= selectMidpointPx
                                else -> next >= selectMidpointPx || next <= deleteTriggerPx
                            }
                            if (wouldFire != triggeredAbove) {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                triggeredAbove = wouldFire
                            }
                            if (next != offsetX - delta) change.consume()
                        },
                    )
                },
        ) {
            BundleRowContent(bundle = bundle, thumbnails = thumbnails)
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

