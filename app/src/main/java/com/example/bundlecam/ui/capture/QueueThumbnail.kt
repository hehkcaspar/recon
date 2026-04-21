package com.example.bundlecam.ui.capture

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

@Composable
fun QueueThumbnail(
    item: StagedItem,
    currentIndex: Int,
    queueSize: Int,
    onDelete: () -> Unit,
    onReorderTo: (Int) -> Unit,
    onDeleteProgress: (progress: Float, hotspotXInRoot: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var verticalDragY by remember { mutableFloatStateOf(0f) }
    var reorderOffsetX by remember { mutableFloatStateOf(0f) }
    var isReordering by remember { mutableStateOf(false) }
    var centerXInRoot by remember { mutableFloatStateOf(0f) }

    val density = LocalDensity.current
    val deleteThresholdPx = with(density) { 40.dp.toPx() }
    val slotPx = with(density) { QueueMetrics.SlotSize.toPx() }

    val alpha = if (verticalDragY > 0f) {
        (1f - verticalDragY / (deleteThresholdPx * 2f)).coerceIn(0.3f, 1f)
    } else 1f

    Box(
        modifier = modifier
            .onGloballyPositioned { coords ->
                centerXInRoot = coords.positionInRoot().x + coords.size.width / 2f
            }
            .zIndex(if (isReordering) 1f else 0f)
            .graphicsLayer {
                translationX = reorderOffsetX
                translationY = verticalDragY.coerceAtLeast(0f)
                this.alpha = alpha
                val scale = if (isReordering) 1.1f else 1f
                scaleX = scale
                scaleY = scale
            }
            .size(QueueMetrics.ThumbSize)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.DarkGray)
            .semantics {
                contentDescription = "Photo ${currentIndex + 1} of $queueSize"
                customActions = buildList {
                    add(CustomAccessibilityAction("Remove") { onDelete(); true })
                    if (currentIndex > 0) {
                        add(CustomAccessibilityAction("Move earlier") {
                            onReorderTo(currentIndex - 1); true
                        })
                    }
                    if (currentIndex < queueSize - 1) {
                        add(CustomAccessibilityAction("Move later") {
                            onReorderTo(currentIndex + 1); true
                        })
                    }
                }
            }
            .pointerInput(item.id) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        if (verticalDragY > deleteThresholdPx) onDelete()
                        verticalDragY = 0f
                        onDeleteProgress(0f, centerXInRoot)
                    },
                    onDragCancel = {
                        verticalDragY = 0f
                        onDeleteProgress(0f, centerXInRoot)
                    },
                    onVerticalDrag = { _, delta ->
                        verticalDragY = (verticalDragY + delta).coerceAtLeast(0f)
                        onDeleteProgress(
                            (verticalDragY / deleteThresholdPx).coerceIn(0f, 1f),
                            centerXInRoot,
                        )
                    },
                )
            }
            .pointerInput(item.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        isReordering = true
                        reorderOffsetX = 0f
                    },
                    onDragEnd = {
                        val shift = (reorderOffsetX / slotPx).roundToInt()
                        val target = (currentIndex + shift).coerceIn(0, queueSize - 1)
                        if (target != currentIndex) onReorderTo(target)
                        isReordering = false
                        reorderOffsetX = 0f
                    },
                    onDragCancel = {
                        isReordering = false
                        reorderOffsetX = 0f
                    },
                    onDrag = { _, drag -> reorderOffsetX += drag.x },
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
                // Play-glyph overlay to distinguish from photos at 56dp.
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .size(20.dp)
                        .align(androidx.compose.ui.Alignment.Center),
                )
                // Duration badge, bottom-right.
                Text(
                    text = formatDuration(item.durationMs),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(androidx.compose.ui.Alignment.BottomEnd)
                        .padding(2.dp)
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(2.dp))
                        .padding(horizontal = 2.dp),
                )
            }
            is StagedItem.Voice -> {
                // Tinted backdrop (the "thumbnail" bitmap is a tiny tinted square —
                // see decodeVoiceThumbnail) plus a larger mic glyph and duration badge.
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
                        .align(androidx.compose.ui.Alignment.Center),
                )
                Text(
                    text = formatDuration(item.durationMs),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(androidx.compose.ui.Alignment.BottomEnd)
                        .padding(2.dp)
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(2.dp))
                        .padding(horizontal = 2.dp),
                )
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}
