package com.example.bundlecam.ui.capture

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.bundlecam.ui.common.ActionBanner

@Composable
fun UndoToast(
    pendingDiscard: PendingDiscard?,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (pendingDiscard == null) return
    val items = pendingDiscard.items
    val count = items.size
    val allPhotos = items.all { it is StagedItem.Photo }
    val noun = if (allPhotos) "photo" else "item"
    val suffix = if (count == 1) "" else "s"
    ActionBanner(
        message = "Discarded $count $noun$suffix",
        actionLabel = "Undo",
        onAction = onUndo,
        modifier = modifier,
    )
}
