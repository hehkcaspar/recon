package com.example.bundlecam.ui.preview

import android.net.Uri
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.ViewStream
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.bundlecam.data.storage.CompletedBundle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Width of the selection reveal zone — the row settles here when selected, exposing
// the green check affordance on the leading edge.
private val SwipeRevealWidth = 80.dp
private const val SWIPE_SELECT_FRACTION = 0.5f
private const val SWIPE_DELETE_FRACTION = 0.4f
private const val SWIPE_VELOCITY_PX_PER_S = 800f
private const val COUNTDOWN_TICK_MS = 200L

private val SelectionGreen = Color(0xFF2E7D32)

private val TileWidth = 56.dp
private val TileHeight = 48.dp
private val TileGap = 4.dp
private val WideThumbRailWidth = 176.dp // 3 × 56dp + 2 × 4dp = 176dp per INTERACTIONS.md
private val CompactThumbRailWidth = 56.dp
private val BundleRowMinHeight = 80.dp

private fun Modifier.bundleRowLayout(): Modifier =
    this
        .fillMaxWidth()
        .heightIn(min = BundleRowMinHeight)
        .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp)

@Composable
fun BundleRow(
    bundle: CompletedBundle,
    thumbnails: Map<Uri, ImageBitmap>,
    pendingDelete: PendingDelete?,
    selected: Boolean,
    processing: Boolean,
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
        processing = processing,
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
    processing: Boolean,
    onRequestDelete: () -> Unit,
    onToggleSelection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val revealWidthPx = with(density) { SwipeRevealWidth.toPx() }
    val trailingMenuZonePx = with(density) { 48.dp.toPx() }

    val currentSelected by rememberUpdatedState(selected)
    val currentProcessing by rememberUpdatedState(processing)
    val currentOnToggleSelection by rememberUpdatedState(onToggleSelection)
    val currentOnRequestDelete by rememberUpdatedState(onRequestDelete)

    var offsetX by remember(bundle.id) {
        mutableFloatStateOf(if (selected) revealWidthPx else 0f)
    }
    val animatable = remember(bundle.id) {
        Animatable(if (selected) revealWidthPx else 0f)
    }
    val velocityTracker = remember(bundle.id) { VelocityTracker() }
    var rowWidthPx by remember(bundle.id) { mutableIntStateOf(0) }
    var isDragging by remember(bundle.id) { mutableStateOf(false) }
    var triggeredAbove by remember(bundle.id) { mutableStateOf(false) }
    var animatingExternally by remember(bundle.id) { mutableStateOf(false) }
    var menuExpanded by remember(bundle.id) { mutableStateOf(false) }

    // External selection changes or processing transition animate/reset the row to its rest target.
    LaunchedEffect(selected, processing, revealWidthPx) {
        if (processing) menuExpanded = false
        val target = if (selected && !processing) revealWidthPx else 0f
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

    // Window / row width change (e.g. rotation, fold, split-screen resize) cancels any
    // in-flight drag displacement back to the row's current rest position.
    LaunchedEffect(rowWidthPx) {
        if (rowWidthPx > 0) {
            val rest = BundleRowSwipe.restOffsetFor(currentSelected && !currentProcessing, revealWidthPx)
            if (!animatingExternally && offsetX != rest) {
                isDragging = false
                animatable.snapTo(rest)
                offsetX = rest
            }
        }
    }

    val containerColor = when {
        offsetX < 0f -> MaterialTheme.colorScheme.errorContainer
        offsetX > 0f -> SelectionGreen
        else -> Color.Transparent
    }

    val fullDescription = "${bundle.id}, ${subtitleFor(bundle)}" +
        when {
            processing -> ", Processing"
            selected -> ", Selected"
            else -> ""
        }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { size ->
                if (rowWidthPx != size.width) {
                    rowWidthPx = size.width
                }
            }
            .background(containerColor)
            .semantics {
                contentDescription = fullDescription
                this.selected = selected
                if (processing) {
                    disabled()
                    customActions = emptyList()
                } else {
                    customActions = if (selected) {
                        listOf(
                            CustomAccessibilityAction("Deselect ${bundle.id}") {
                                onToggleSelection()
                                true
                            },
                        )
                    } else {
                        listOf(
                            CustomAccessibilityAction("Select ${bundle.id}") {
                                onToggleSelection()
                                true
                            },
                            CustomAccessibilityAction("Delete ${bundle.id}") {
                                onRequestDelete()
                                true
                            },
                        )
                    }
                }
            },
    ) {
        val isWideRow = maxWidth >= 520.dp

        if (offsetX < 0f && rowWidthPx > 0) {
            DeleteHint(
                color = MaterialTheme.colorScheme.onErrorContainer,
                progress = (-offsetX / rowWidthPx.toFloat()).coerceIn(0f, 1f),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 16.dp),
            )
        }
        if (offsetX > 0f) {
            SelectionRevealZone(
                bundleId = bundle.id,
                tappable = selected && !processing,
                onTap = onToggleSelection,
            )
        }

        val gestureModifier = if (processing) {
            Modifier
        } else {
            Modifier.pointerInput(bundle.id) {
                var dragSuppressed = false

                detectHorizontalDragGestures(
                    onDragStart = { startOffset ->
                        val widthF = size.width.toFloat()
                        val validStart = BundleRowSwipe.isValidSwipeStart(
                            startXInSurface = startOffset.x,
                            rowWidthPx = widthF,
                            trailingMenuZonePx = trailingMenuZonePx,
                        )
                        dragSuppressed = animatingExternally || currentProcessing || !validStart
                        if (!dragSuppressed) {
                            isDragging = true
                            triggeredAbove = false
                            velocityTracker.resetTracking()
                        }
                    },
                    onDragEnd = {
                        if (dragSuppressed || !isDragging) {
                            isDragging = false
                            return@detectHorizontalDragGestures
                        }
                        isDragging = false
                        val widthF = size.width.toFloat().coerceAtLeast(1f)
                        val deleteTriggerPx = -widthF * SWIPE_DELETE_FRACTION
                        val selectMidpointPx = revealWidthPx * SWIPE_SELECT_FRACTION
                        val release = BundleRowSwipe.computeRelease(
                            offsetX = offsetX,
                            velocity = velocityTracker.calculateVelocity().x,
                            selected = currentSelected,
                            revealWidthPx = revealWidthPx,
                            deleteTriggerPx = deleteTriggerPx,
                            selectMidpointPx = selectMidpointPx,
                        )
                        coroutineScope.launch {
                            animatable.snapTo(offsetX)
                            animatable.animateTo(release.targetOffset) { offsetX = value }
                            when (release.action) {
                                BundleRowSwipe.SwipeAction.None -> Unit
                                BundleRowSwipe.SwipeAction.ToggleSelection ->
                                    currentOnToggleSelection()
                                BundleRowSwipe.SwipeAction.RequestDelete -> {
                                    currentOnRequestDelete()
                                    animatable.snapTo(0f)
                                    offsetX = 0f
                                }
                            }
                        }
                    },
                    onDragCancel = {
                        if (dragSuppressed) return@detectHorizontalDragGestures
                        isDragging = false
                        coroutineScope.launch {
                            val rest = BundleRowSwipe.restOffsetFor(currentSelected, revealWidthPx)
                            animatable.snapTo(offsetX)
                            animatable.animateTo(rest) { offsetX = value }
                        }
                    },
                    onHorizontalDrag = { change, delta ->
                        if (dragSuppressed || currentProcessing) {
                            change.consume()
                            return@detectHorizontalDragGestures
                        }
                        val widthF = size.width.toFloat().coerceAtLeast(1f)
                        val deleteTriggerPx = -widthF * SWIPE_DELETE_FRACTION
                        val selectMidpointPx = revealWidthPx * SWIPE_SELECT_FRACTION
                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                        val prev = offsetX
                        val next = BundleRowSwipe.clampOffset(
                            rawOffset = prev + delta,
                            selected = currentSelected,
                            revealWidthPx = revealWidthPx,
                        )
                        offsetX = next
                        val wouldFire = BundleRowSwipe.wouldFireOnRelease(
                            offsetX = next,
                            selected = currentSelected,
                            deleteTriggerPx = deleteTriggerPx,
                            selectMidpointPx = selectMidpointPx,
                        )
                        if (wouldFire != triggeredAbove) {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            triggeredAbove = wouldFire
                        }
                        if (next != prev) change.consume()
                    },
                )
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = offsetX }
                .then(gestureModifier),
        ) {
            BundleRowContent(
                bundle = bundle,
                thumbnails = thumbnails,
                expandedThumbRail = isWideRow,
                selected = selected,
                processing = processing,
                menuExpanded = menuExpanded,
                onOpenMenu = { if (!processing) menuExpanded = true },
                onDismissMenu = { menuExpanded = false },
                onToggleSelectionFromMenu = {
                    menuExpanded = false
                    if (!processing) onToggleSelection()
                },
                onRequestDeleteFromMenu = {
                    menuExpanded = false
                    if (!processing && !selected) onRequestDelete()
                },
            )
        }
    }
}

/**
 * 80-dp wide leading-edge tap zone showing the green check affordance. Composed only
 * when the row is offset right (offsetX > 0); becomes click-active when the row is
 * at-rest selected, so tapping the green strip deselects.
 */
@Composable
private fun BoxScope.SelectionRevealZone(
    bundleId: String,
    tappable: Boolean,
    onTap: () -> Unit,
) {
    Box(
        modifier = Modifier
            .align(Alignment.CenterStart)
            .width(SwipeRevealWidth)
            .heightIn(min = BundleRowMinHeight)
            .semantics {
                if (tappable) {
                    contentDescription = "Deselect $bundleId"
                }
            }
            .clickable(
                enabled = tappable,
                role = Role.Button,
                onClickLabel = "Deselect $bundleId",
            ) { onTap() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = if (tappable) "Deselect bundle" else null,
            tint = Color.White,
            modifier = Modifier.size(24.dp),
        )
    }
}

/**
 * Pure swipe and menu decision logic — no Compose dependency, fully unit-testable.
 */
internal object BundleRowSwipe {

    enum class SwipeAction { None, ToggleSelection, RequestDelete }

    enum class MenuOption { Select, Deselect, Delete }

    data class SwipeRelease(
        val targetOffset: Float,
        val action: SwipeAction,
    )

    fun restOffsetFor(selected: Boolean, revealWidthPx: Float): Float =
        if (selected) revealWidthPx else 0f

    /**
     * Checks that a swipe starts on the row body clear of the trailing 48dp More menu.
     */
    fun isValidSwipeStart(
        startXInSurface: Float,
        rowWidthPx: Float,
        trailingMenuZonePx: Float,
    ): Boolean {
        if (rowWidthPx <= trailingMenuZonePx) return true
        return startXInSurface in 0f..(rowWidthPx - trailingMenuZonePx)
    }

    /**
     * Direct `More` menu options per `INTERACTIONS.md`:
     * - Processing or pending-delete rows have no menu options.
     * - Ready unselected rows offer `Select` and `Delete`.
     * - Ready selected rows offer `Deselect` only (a selected row cannot be deleted without deselecting).
     */
    fun menuOptionsFor(
        selected: Boolean,
        processing: Boolean,
        pendingDelete: Boolean = false,
    ): List<MenuOption> {
        if (processing || pendingDelete) return emptyList()
        return if (selected) {
            listOf(MenuOption.Deselect)
        } else {
            listOf(MenuOption.Select, MenuOption.Delete)
        }
    }

    /**
     * Middle-ellipsis formatting when a long bundle ID would crowd a narrow row
     * (e.g. `2026-11-02-s-10027` -> `2026…10027`), preserving the sequence number
     * visually while screen readers still announce the full `bundle.id`.
     */
    fun displayTitleFor(bundleId: String, availableWidthDp: Float): String {
        if (bundleId.length * 7.7f <= availableWidthDp || !bundleId.contains('-')) {
            return bundleId
        }
        val prefix = bundleId.take(4)
        val suffix = bundleId.substringAfterLast('-')
        return "$prefix…$suffix"
    }

    fun computeRelease(
        offsetX: Float,
        velocity: Float,
        selected: Boolean,
        revealWidthPx: Float,
        deleteTriggerPx: Float,
        selectMidpointPx: Float,
    ): SwipeRelease {
        val isFastRight = velocity >= SWIPE_VELOCITY_PX_PER_S
        val isFastLeft = velocity <= -SWIPE_VELOCITY_PX_PER_S
        return when {
            selected &&
                (offsetX <= selectMidpointPx ||
                    (isFastLeft && offsetX < revealWidthPx)) ->
                SwipeRelease(0f, SwipeAction.ToggleSelection)
            !selected && offsetX > 0f &&
                (offsetX >= selectMidpointPx || isFastRight) ->
                SwipeRelease(revealWidthPx, SwipeAction.ToggleSelection)
            !selected && offsetX < 0f &&
                (offsetX <= deleteTriggerPx || isFastLeft) ->
                SwipeRelease(deleteTriggerPx, SwipeAction.RequestDelete)
            else ->
                SwipeRelease(if (selected) revealWidthPx else 0f, SwipeAction.None)
        }
    }

    fun clampOffset(rawOffset: Float, selected: Boolean, revealWidthPx: Float): Float =
        if (selected) rawOffset.coerceIn(0f, revealWidthPx)
        else rawOffset.coerceAtMost(revealWidthPx)

    fun wouldFireOnRelease(
        offsetX: Float,
        selected: Boolean,
        deleteTriggerPx: Float,
        selectMidpointPx: Float,
    ): Boolean = when {
        selected -> offsetX <= selectMidpointPx
        else -> offsetX >= selectMidpointPx || offsetX <= deleteTriggerPx
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
    expandedThumbRail: Boolean,
    selected: Boolean,
    processing: Boolean,
    menuExpanded: Boolean,
    onOpenMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onToggleSelectionFromMenu: () -> Unit,
    onRequestDeleteFromMenu: () -> Unit,
) {
    Row(
        modifier = Modifier.bundleRowLayout(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ThumbnailStrip(
            uris = bundle.thumbnailUris,
            thumbnails = thumbnails,
            expanded = expandedThumbRail,
        )

        Spacer(Modifier.width(12.dp))

        BoxWithConstraints(modifier = Modifier.weight(1f)) {
            val shownTitle = BundleRowSwipe.displayTitleFor(bundle.id, maxWidth.value)
            Column {
                Text(
                    text = shownTitle,
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
        }

        Spacer(Modifier.width(6.dp))

        ModalityIcons(bundle = bundle)

        if (!processing) {
            val options = BundleRowSwipe.menuOptionsFor(selected = selected, processing = false)
            Box {
                IconButton(
                    onClick = onOpenMenu,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "Actions for ${bundle.id}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = onDismissMenu,
                ) {
                    options.forEach { option ->
                        when (option) {
                            BundleRowSwipe.MenuOption.Select -> {
                                DropdownMenuItem(
                                    text = { Text("Select") },
                                    leadingIcon = {
                                        Icon(Icons.Filled.Check, contentDescription = null)
                                    },
                                    onClick = onToggleSelectionFromMenu,
                                    modifier = Modifier.semantics {
                                        contentDescription = "Select ${bundle.id}"
                                    },
                                )
                            }
                            BundleRowSwipe.MenuOption.Deselect -> {
                                DropdownMenuItem(
                                    text = { Text("Deselect") },
                                    leadingIcon = {
                                        Icon(Icons.Filled.Check, contentDescription = null)
                                    },
                                    onClick = onToggleSelectionFromMenu,
                                    modifier = Modifier.semantics {
                                        contentDescription = "Deselect ${bundle.id}"
                                    },
                                )
                            }
                            BundleRowSwipe.MenuOption.Delete -> {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = "Delete",
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Filled.Delete,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                    onClick = onRequestDeleteFromMenu,
                                    modifier = Modifier.semantics {
                                        contentDescription = "Delete ${bundle.id}"
                                    },
                                )
                            }
                        }
                    }
                }
            }
        } else {
            Spacer(Modifier.size(48.dp))
        }
    }
}

@Composable
private fun ThumbnailStrip(
    uris: List<Uri>,
    thumbnails: Map<Uri, ImageBitmap>,
    expanded: Boolean,
) {
    val railWidth = if (expanded) WideThumbRailWidth else CompactThumbRailWidth
    val maxTiles = if (expanded) 3 else 1
    Row(
        modifier = Modifier.width(railWidth),
        horizontalArrangement = Arrangement.spacedBy(TileGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        uris.take(maxTiles).forEach { uri ->
            Box(
                modifier = Modifier
                    .size(width = TileWidth, height = TileHeight)
                    .clip(RoundedCornerShape(7.dp))
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
    val secondsLeft by remember(expiresAtMillis) {
        derivedStateOf { ((expiresAtMillis - nowMs) / 1000L).coerceAtLeast(0L).toInt() + 1 }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "${bundle.id}, Deleting in ${secondsLeft}s"
                customActions = listOf(
                    CustomAccessibilityAction("Undo deletion of ${bundle.id}") {
                        onUndo()
                        true
                    },
                )
            },
    ) {
        val isWideRow = maxWidth >= 520.dp
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = BundleRowMinHeight)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ThumbnailStrip(
                uris = bundle.thumbnailUris,
                thumbnails = thumbnails,
                expanded = isWideRow,
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
                    text = "Deleting in ${secondsLeft}s",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.width(8.dp))

            FilledTonalButton(
                onClick = onUndo,
                modifier = Modifier
                    .widthIn(min = 72.dp)
                    .heightIn(min = 48.dp)
                    .semantics {
                        contentDescription = "Undo deletion of ${bundle.id}"
                    },
            ) {
                Text("Undo")
            }
        }
    }
}

@Composable
fun ProcessingBundleRow(
    bundleId: String,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "$bundleId, Processing"
                disabled()
            },
    ) {
        val isWideRow = maxWidth >= 520.dp
        val railWidth = if (isWideRow) WideThumbRailWidth else CompactThumbRailWidth
        Row(
            modifier = Modifier.bundleRowLayout(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.width(railWidth),
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(
                    modifier = Modifier
                        .size(width = TileWidth, height = TileHeight)
                        .clip(RoundedCornerShape(7.dp))
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
}
