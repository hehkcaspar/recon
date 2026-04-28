package com.example.bundlecam.ui.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.bundlecam.data.storage.CompletedBundle
import com.example.bundlecam.di.AppContainer
import com.example.bundlecam.network.localsend.Peer
import com.example.bundlecam.network.localsend.SendBundleResult
import com.example.bundlecam.network.localsend.SendProgress
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Production multi-bundle send sheet — opens from the bundles browser's contextual app
 * bar with the user-selected bundles. Discovers peers, lets the user pick one, ships
 * each bundle sequentially (peers reject parallel sessions with HTTP 409 BLOCKED), and
 * surfaces per-bundle progress + aggregate outcomes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalSendSheet(
    container: AppContainer,
    bundles: List<CompletedBundle>,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    val peers = remember { mutableStateListOf<Peer>() }
    var ui by remember { mutableStateOf<SendUiState>(SendUiState.Discovering) }
    var sendJob by remember { mutableStateOf<Job?>(null) }

    fun closeAndDismiss() {
        scope.launch {
            sendJob?.cancel()
            container.localSendController.stopDiscovery()
            onDismiss()
        }
    }

    LaunchedEffect(Unit) {
        container.localSendController.discover(scope).collect { peer ->
            if (peers.none { it.fingerprint == peer.fingerprint }) peers.add(peer)
        }
    }

    ModalBottomSheet(
        onDismissRequest = { closeAndDismiss() },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Header(bundles = bundles, ui = ui)
            Spacer(Modifier.height(16.dp))

            when (val s = ui) {
                SendUiState.Discovering -> PeerListBlock(
                    peers = peers,
                    onPick = { peer ->
                        sendJob = scope.launch { runSendQueue(container, peer, bundles) { ui = it } }
                    },
                )
                is SendUiState.Sending -> SendingBlock(s)
                is SendUiState.Done -> DoneBlock(
                    state = s,
                    onDone = { closeAndDismiss() },
                )
            }
        }
    }
}

private suspend fun runSendQueue(
    container: AppContainer,
    peer: Peer,
    bundles: List<CompletedBundle>,
    set: (SendUiState) -> Unit,
) {
    val results = mutableListOf<BundleSendOutcome>()
    bundles.forEachIndexed { index, bundle ->
        set(
            SendUiState.Sending(
                peerAlias = peer.alias,
                totalBundles = bundles.size,
                currentIndex = index,
                currentBundleId = bundle.id,
                progress = null,
                results = results.toList(),
            )
        )
        val result = container.localSendController.send(
            peer = peer,
            bundle = bundle,
            onProgress = { p ->
                set(
                    SendUiState.Sending(
                        peerAlias = peer.alias,
                        totalBundles = bundles.size,
                        currentIndex = index,
                        currentBundleId = bundle.id,
                        progress = p,
                        results = results.toList(),
                    )
                )
            },
        )
        results += BundleSendOutcome(bundle.id, result)
    }
    set(SendUiState.Done(results = results.toList()))
}

private sealed interface SendUiState {
    data object Discovering : SendUiState
    data class Sending(
        val peerAlias: String,
        val totalBundles: Int,
        val currentIndex: Int,
        val currentBundleId: String,
        val progress: SendProgress?,
        val results: List<BundleSendOutcome>,
    ) : SendUiState
    data class Done(val results: List<BundleSendOutcome>) : SendUiState
}

private data class BundleSendOutcome(val bundleId: String, val result: SendBundleResult)

@Composable
private fun Header(bundles: List<CompletedBundle>, ui: SendUiState) {
    val title = when (ui) {
        SendUiState.Discovering -> when (bundles.size) {
            1 -> "Send 1 bundle"
            else -> "Send ${bundles.size} bundles"
        }
        is SendUiState.Sending -> "Sending to ${ui.peerAlias}"
        is SendUiState.Done -> {
            val ok = ui.results.count { it.result is SendBundleResult.Success || it.result is SendBundleResult.AlreadyReceived }
            val total = ui.results.size
            if (ok == total) "Sent $total ✓" else "Sent $ok / $total"
        }
    }
    Text(text = title, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(2.dp))
    val subtitle = when (ui) {
        SendUiState.Discovering -> "Pick a peer on the same Wi-Fi network."
        is SendUiState.Sending ->
            "Bundle ${ui.currentIndex + 1} of ${ui.totalBundles} · ${ui.currentBundleId}"
        is SendUiState.Done -> "Tap close to return to the bundles list."
    }
    Text(
        text = subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PeerListBlock(peers: List<Peer>, onPick: (Peer) -> Unit) {
    if (peers.isEmpty()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(
                text = "  Looking for peers on Wi-Fi…",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(peers, key = { it.fingerprint }) { peer ->
                TextButton(
                    onClick = { onPick(peer) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(peer.alias, style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = buildString {
                                peer.deviceModel?.let { append(it) }
                                if (peer.deviceModel != null) append(" · ")
                                append(peer.fingerprint.take(8))
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SendingBlock(s: SendUiState.Sending) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Aggregate progress: bytes-completed-across-all-bundles / total-bytes is most
        // user-meaningful, but we don't have that without enumerating every file in
        // every queued bundle upfront. Use bundle-count progress as the outer bar +
        // per-file progress within the active bundle.
        val outerFrac = (s.currentIndex.toFloat() / s.totalBundles.toFloat()).coerceIn(0f, 1f)
        LinearProgressIndicator(
            progress = { outerFrac },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        val p = s.progress
        if (p == null || p.totalBytes == 0L) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        } else {
            val frac = (p.sentBytes.toFloat() / p.totalBytes.toFloat()).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { frac },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "File ${p.completedFiles}/${p.totalFiles} · ${p.currentFileName ?: "…"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DoneBlock(state: SendUiState.Done, onDone: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Per-bundle outcomes — useful when one bundle fails inside a multi-bundle
        // send so the user can see which one(s).
        state.results.forEach { outcome ->
            val (label, color) = outcomeLabel(outcome.result)
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = outcome.bundleId,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = color,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onDone) { Text("Close") }
        }
    }
}

@Composable
private fun outcomeLabel(result: SendBundleResult): Pair<String, androidx.compose.ui.graphics.Color> =
    when (result) {
        SendBundleResult.Success -> "sent" to MaterialTheme.colorScheme.primary
        SendBundleResult.AlreadyReceived -> "already received" to MaterialTheme.colorScheme.onSurfaceVariant
        SendBundleResult.Cancelled -> "cancelled" to MaterialTheme.colorScheme.onSurfaceVariant
        is SendBundleResult.Failed -> "failed: ${result.message}" to MaterialTheme.colorScheme.error
    }
