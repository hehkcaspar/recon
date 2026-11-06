package com.example.bundlecam.ui.capture

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.example.bundlecam.ui.common.formatClockDuration
import kotlin.math.abs

val StagedItem.mediaTypeLabel: String
    get() = when (this) {
        is StagedItem.Photo -> "Photo"
        is StagedItem.Video -> "Video"
        is StagedItem.Voice -> "Voice"
    }

@Composable
fun QueueThumbnail(
    item: StagedItem,
    currentIndex: Int,
    queueSize: Int,
    axis: QueueAxis,
    profile: QueueProfileToken,
    hasDividerAfter: Boolean,
    locked: Boolean,
    slotCentersProvider: () -> FloatArray,
    onTapOpenMenu: () -> Unit,
    onDelete: () -> Unit,
    onReorderTo: (Int) -> Unit,
    onToggleSplitAfter: () -> Unit,
    onCommitQueue: () -> Unit,
    onDiscardQueue: () -> Unit,
    onDeleteProgress: (progress: Float, hotspotInRoot: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var crossDragPx by remember(item.id, axis, profile) { mutableFloatStateOf(0f) }
    var reorderPrimaryOffsetPx by remember(item.id, axis, profile) { mutableFloatStateOf(0f) }
    var isReordering by remember(item.id, axis, profile) { mutableStateOf(false) }
    var thumbCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var centerInRootPrimary by remember { mutableFloatStateOf(0f) }

    fun liveCenterInRootPrimary(): Float {
        val coords = thumbCoords?.takeIf { it.isAttached } ?: return centerInRootPrimary
        val pos = coords.positionInRoot()
        return if (axis == QueueAxis.Horizontal) {
            pos.x + coords.size.width / 2f
        } else {
            pos.y + coords.size.height / 2f
        }
    }

    val density = LocalDensity.current
    val viewConfig = LocalViewConfiguration.current
    val deleteGatePx = with(density) { QueueGestureMath.DELETE_GATE_DP.dp.toPx() }
    val maxDeleteVisualPx = with(density) { QueueGestureMath.DELETE_MAX_VISUAL_DP.dp.toPx() }

    val currentIndexState by rememberUpdatedState(currentIndex)
    val queueSizeState by rememberUpdatedState(queueSize)
    val slotCentersProviderState by rememberUpdatedState(slotCentersProvider)
    val lockedState by rememberUpdatedState(locked)
    val onDeleteState by rememberUpdatedState(onDelete)
    val onReorderToState by rememberUpdatedState(onReorderTo)
    val onTapOpenMenuState by rememberUpdatedState(onTapOpenMenu)

    // If locked or geometry changes mid-gesture, cancel visual state without mutating.
    LaunchedEffect(locked, axis, profile) {
        if (locked) {
            crossDragPx = 0f
            isReordering = false
            reorderPrimaryOffsetPx = 0f
            onDeleteProgress(0f, centerInRootPrimary)
        }
    }

    val clampedVisualCrossPx = QueueGestureMath.clampedDeleteVisualPx(crossDragPx, maxDeleteVisualPx)
    val alpha = QueueGestureMath.deleteItemAlpha(clampedVisualCrossPx, maxDeleteVisualPx)
    val description = "${item.mediaTypeLabel} ${currentIndex + 1} of $queueSize"

    Box(
        modifier = modifier
            .onGloballyPositioned { coords ->
                thumbCoords = coords
                val pos = coords.positionInRoot()
                centerInRootPrimary = if (axis == QueueAxis.Horizontal) {
                    pos.x + coords.size.width / 2f
                } else {
                    pos.y + coords.size.height / 2f
                }
            }
            .zIndex(if (isReordering) 1f else 0f)
            .graphicsLayer {
                if (axis == QueueAxis.Horizontal) {
                    translationX = reorderPrimaryOffsetPx
                    translationY = clampedVisualCrossPx
                } else {
                    translationX = clampedVisualCrossPx
                    translationY = reorderPrimaryOffsetPx
                }
                this.alpha = alpha
                val scale = if (isReordering) 1.1f else 1f
                scaleX = scale
                scaleY = scale
            }
            .size(profile.mediaDp.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.DarkGray)
            .semantics {
                contentDescription = description
                if (locked) {
                    disabled()
                    customActions = emptyList()
                } else {
                    customActions = buildList {
                        add(CustomAccessibilityAction("Remove item") { onDelete(); true })
                        if (currentIndex > 0) {
                            add(CustomAccessibilityAction("Move earlier") {
                                onReorderTo(currentIndex - 1); true
                            })
                        }
                        if (currentIndex < queueSize - 1) {
                            add(CustomAccessibilityAction("Move later") {
                                onReorderTo(currentIndex + 1); true
                            })
                            val splitLabel = if (hasDividerAfter) "Remove split" else "Split after"
                            add(CustomAccessibilityAction(splitLabel) {
                                onToggleSplitAfter(); true
                            })
                        }
                        add(CustomAccessibilityAction("Save queue") { onCommitQueue(); true })
                        add(CustomAccessibilityAction("Discard queue") { onDiscardQueue(); true })
                    }
                }
            }
            .clickable(
                enabled = !locked,
                onClickLabel = "Open actions for $description",
                role = Role.Button,
                onClick = { if (!lockedState) onTapOpenMenuState() },
            )
            .pointerInput(item.id, axis, profile, locked) {
                if (locked) return@pointerInput
                val slop = viewConfig.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    crossDragPx = 0f
                    var totalPrimary = 0f
                    var totalCross = 0f
                    var ownedCrossDelete = false
                    var rejectedForPrimaryScroll = false
                    var cancelled = false

                    while (true) {
                        val event = awaitPointerEvent()
                        if (lockedState || event.changes.count { it.pressed } > 1) {
                            cancelled = true
                            break
                        }
                        val change = event.changes.firstOrNull { it.id == down.id } ?: run {
                            cancelled = true
                            break
                        }
                        if (change.changedToUp()) {
                            if (ownedCrossDelete && !cancelled) {
                                change.consume()
                                if (QueueGestureMath.shouldCommitDelete(
                                        netCrossPx = crossDragPx,
                                        deleteGatePx = deleteGatePx,
                                        isCancelled = false,
                                        isLocked = lockedState,
                                    )
                                ) {
                                    onDeleteState()
                                }
                            }
                            break
                        }
                        if (change.isConsumed && !ownedCrossDelete) {
                            cancelled = true
                            break
                        }
                        val delta = change.positionChange()
                        val dPrimary = if (axis == QueueAxis.Horizontal) delta.x else delta.y
                        val dCross = if (axis == QueueAxis.Horizontal) delta.y else delta.x
                        totalPrimary += dPrimary
                        totalCross += dCross

                        if (!ownedCrossDelete && !rejectedForPrimaryScroll) {
                            if (abs(totalPrimary) > slop || abs(totalCross) > slop) {
                                if (abs(totalCross) > abs(totalPrimary) && totalCross > 0f) {
                                    ownedCrossDelete = true
                                } else {
                                    rejectedForPrimaryScroll = true
                                }
                            }
                        }

                        if (ownedCrossDelete) {
                            change.consume()
                            crossDragPx = totalCross.coerceAtLeast(0f)
                            val liveHotspot = liveCenterInRootPrimary()
                            centerInRootPrimary = liveHotspot
                            onDeleteProgress(
                                QueueGestureMath.deleteProgress(crossDragPx, deleteGatePx),
                                liveHotspot,
                            )
                        }
                    }

                    crossDragPx = 0f
                    onDeleteProgress(0f, liveCenterInRootPrimary())
                }
            }
            .pointerInput(item.id, axis, profile, locked) {
                if (locked) return@pointerInput
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        if (!lockedState) {
                            isReordering = true
                            reorderPrimaryOffsetPx = 0f
                        }
                    },
                    onDragEnd = {
                        if (isReordering && !lockedState) {
                            val target = QueueGestureMath.computeReorderDestinationIndex(
                                currentIndex = currentIndexState,
                                dragPrimaryPx = reorderPrimaryOffsetPx,
                                slotCentersPx = slotCentersProviderState(),
                                fallbackPitchPx = with(density) { (profile.mediaDp + profile.gapDp).dp.toPx() },
                                queueSize = queueSizeState,
                            )
                            if (target != currentIndexState) {
                                onReorderToState(target)
                            }
                        }
                        isReordering = false
                        reorderPrimaryOffsetPx = 0f
                    },
                    onDragCancel = {
                        isReordering = false
                        reorderPrimaryOffsetPx = 0f
                    },
                    onDrag = { change, drag ->
                        if (!lockedState && isReordering) {
                            change.consume()
                            reorderPrimaryOffsetPx += if (axis == QueueAxis.Horizontal) drag.x else drag.y
                        }
                    },
                )
            },
    ) {
        when (item) {
            is StagedItem.Photo -> Image(
                bitmap = item.thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            is StagedItem.Video -> {
                Image(
                    bitmap = item.thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .size(20.dp)
                        .align(Alignment.Center),
                )
                DurationBadge(item.durationMs)
            }
            is StagedItem.Voice -> {
                Image(
                    bitmap = item.thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Icon(
                    imageVector = Icons.Filled.Mic,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .size(28.dp)
                        .align(Alignment.Center),
                )
                DurationBadge(item.durationMs)
            }
        }
    }
}

@Composable
private fun BoxScope.DurationBadge(durationMs: Long) {
    Text(
        text = formatClockDuration(durationMs),
        color = Color.White,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(2.dp)
            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(2.dp))
            .padding(horizontal = 2.dp),
    )
}
