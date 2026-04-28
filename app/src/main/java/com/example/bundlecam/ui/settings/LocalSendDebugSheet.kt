package com.example.bundlecam.ui.settings

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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Single-bundle send sheet — opens from the bundles browser's contextual app bar (with
 * the first selected bundle) and from the Settings debug entry (with the most-recent
 * completed bundle). Phase 4 will introduce a multi-bundle variant that supersedes this
 * for the bundles-browser path; the Settings debug entry will be removed at the same
 * time.
 *
 * Discovers peers, lets the user pick one, ships [bundle] to the picked peer, surfaces
 * progress + the final [SendBundleResult].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalSendDebugSheet(
    container: AppContainer,
    bundle: CompletedBundle?,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    val peers = remember { mutableStateListOf<Peer>() }
    var sendState by remember { mutableStateOf<DebugSendState>(DebugSendState.Idle) }

    fun closeAndDismiss() {
        scope.launch {
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
            Text(
                text = if (bundle != null) "Send ${bundle.id}" else "LocalSend",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "Pick a peer on the same Wi-Fi network.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            when (val s = sendState) {
                DebugSendState.Idle -> {
                    if (bundle == null) {
                        ErrorBlock(
                            message = "No completed bundle to send.",
                            onDone = { closeAndDismiss() },
                        )
                    } else {
                        PeerListBlock(
                            peers = peers,
                            onPick = { peer ->
                                scope.launch {
                                    sendState = DebugSendState.Sending(
                                        peerAlias = peer.alias,
                                        bundleId = bundle.id,
                                        progress = null,
                                    )
                                    val result = container.localSendController.send(
                                        peer = peer,
                                        bundle = bundle,
                                        onProgress = { p ->
                                            val cur = sendState
                                            if (cur is DebugSendState.Sending) {
                                                sendState = cur.copy(progress = p)
                                            }
                                        },
                                    )
                                    sendState = DebugSendState.Done(result)
                                }
                            },
                        )
                    }
                }
                DebugSendState.Resolving -> CenteredSpinner("Resolving bundle…")
                is DebugSendState.Sending -> SendingBlock(s)
                is DebugSendState.Done -> DoneBlock(
                    result = s.result,
                    onDone = { closeAndDismiss() },
                )
                is DebugSendState.Error -> ErrorBlock(
                    message = s.message,
                    onDone = { closeAndDismiss() },
                )
            }
        }
    }
}

private sealed interface DebugSendState {
    data object Idle : DebugSendState
    data object Resolving : DebugSendState
    data class Sending(
        val peerAlias: String,
        val bundleId: String,
        val progress: SendProgress?,
    ) : DebugSendState
    data class Done(val result: SendBundleResult) : DebugSendState
    data class Error(val message: String) : DebugSendState
}

@Composable
private fun PeerListBlock(peers: List<Peer>, onPick: (Peer) -> Unit) {
    if (peers.isEmpty()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.height(0.dp))
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
private fun CenteredSpinner(message: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(
            text = "  $message",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun SendingBlock(s: DebugSendState.Sending) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Sending ${s.bundleId} to ${s.peerAlias}…",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        val p = s.progress
        if (p == null || p.totalBytes == 0L) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
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
private fun DoneBlock(result: SendBundleResult, onDone: () -> Unit) {
    val text = when (result) {
        SendBundleResult.Success -> "Sent ✓"
        SendBundleResult.AlreadyReceived -> "Peer already had this bundle"
        SendBundleResult.Cancelled -> "Cancelled"
        is SendBundleResult.Failed -> "Failed: ${result.message}"
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
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
private fun ErrorBlock(message: String, onDone: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onDone) { Text("Close") }
        }
    }
}
