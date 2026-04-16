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
    val count = pendingDiscard.items.size
    val suffix = if (count == 1) "" else "s"
    ActionBanner(
        message = "Discarded $count photo$suffix",
        actionLabel = "Undo",
        onAction = onUndo,
        modifier = modifier,
    )
}
