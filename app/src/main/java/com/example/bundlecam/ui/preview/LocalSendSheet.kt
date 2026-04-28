package com.example.bundlecam.ui.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.mutableIntStateOf
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val DISCOVERY_TIMEOUT_MS = 12_000L

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
    var ui by remember { mutableStateOf<SendUiState>(SendUiState.Discovering(timedOut = false)) }
    var sendJob by remember { mutableStateOf<Job?>(null) }
    // Bumped on retry — re-keys both LaunchedEffects so we get a fresh discovery
    // session and a fresh timeout countdown.
    var discoveryAttempt by remember { mutableIntStateOf(0) }

    fun closeAndDismiss() {
        // Dismiss the sheet immediately so the modal scrim stops intercepting touches
        // on the bundles page underneath. The cleanup (cancel send + stop discovery)
        // runs on the AppContainer's process-scoped appScope so it survives this
        // sheet's coroutineScope being cancelled when the parent removes us from the
        // composition. Without this, a slow stopDiscovery (the receive loop's soTimeout
        // can hold up cancelAndJoin for ~1 s) freezes the bundle page until cleanup
        // finishes, even though there's nothing the user can do during that wait.
        sendJob?.cancel()
        container.appScope.launch { container.localSendController.stopDiscovery() }
        onDismiss()
    }

    LaunchedEffect(discoveryAttempt) {
        container.localSendController.discover(scope).collect { peer ->
            if (peers.none { it.fingerprint == peer.fingerprint }) peers.add(peer)
        }
    }

    LaunchedEffect(discoveryAttempt) {
        delay(DISCOVERY_TIMEOUT_MS)
        if (peers.isEmpty() && ui is SendUiState.Discovering) {
            ui = SendUiState.Discovering(timedOut = true)
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
                is SendUiState.Discovering -> PeerListBlock(
                    peers = peers,
                    timedOut = s.timedOut,
                    onPick = { peer ->
                        sendJob = scope.launch { runSend(container, peer, bundles) { ui = it } }
                    },
                    onRetry = {
                        scope.launch {
                            container.localSendController.stopDiscovery()
                            peers.clear()
                            ui = SendUiState.Discovering(timedOut = false)
                            discoveryAttempt++
                        }
                    },
                    onClose = { closeAndDismiss() },
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

private suspend fun runSend(
    container: AppContainer,
    peer: Peer,
    bundles: List<CompletedBundle>,
    set: (SendUiState) -> Unit,
) {
    set(
        SendUiState.Sending(
            peerAlias = peer.alias,
            bundleCount = bundles.size,
            progress = null,
        )
    )
    val result = container.localSendController.send(
        peer = peer,
        bundles = bundles,
        onProgress = { p ->
            set(
                SendUiState.Sending(
                    peerAlias = peer.alias,
                    bundleCount = bundles.size,
                    progress = p,
                )
            )
        },
    )
    set(SendUiState.Done(bundleCount = bundles.size, result = result))
}

private sealed interface SendUiState {
    data class Discovering(val timedOut: Boolean) : SendUiState
    data class Sending(
        val peerAlias: String,
        val bundleCount: Int,
        val progress: SendProgress?,
    ) : SendUiState
    data class Done(
        val bundleCount: Int,
        val result: SendBundleResult,
    ) : SendUiState
}

@Composable
private fun Header(bundles: List<CompletedBundle>, ui: SendUiState) {
    val bundleNoun = if (bundles.size == 1) "bundle" else "bundles"
    val title = when (ui) {
        is SendUiState.Discovering -> "Send ${bundles.size} $bundleNoun"
        is SendUiState.Sending -> "Sending ${ui.bundleCount} $bundleNoun to ${ui.peerAlias}"
        is SendUiState.Done -> when (ui.result) {
            SendBundleResult.Success -> "Sent ${ui.bundleCount} $bundleNoun ✓"
            SendBundleResult.AlreadyReceived -> "Peer already had this content"
            is SendBundleResult.Failed -> "Send failed"
        }
    }
    Text(text = title, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(2.dp))
    val subtitle = when (ui) {
        is SendUiState.Discovering -> "Pick a peer on the same Wi-Fi network."
        is SendUiState.Sending -> {
            val p = ui.progress
            if (p == null) "Negotiating with peer…"
            else "${p.completedFiles} of ${p.totalFiles} files"
        }
        is SendUiState.Done -> "Tap close to return to the bundles list."
    }
    Text(
        text = subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PeerListBlock(
    peers: List<Peer>,
    timedOut: Boolean,
    onPick: (Peer) -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    when {
        peers.isEmpty() && !timedOut -> Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(
                text = "  Looking for peers on Wi-Fi…",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        peers.isEmpty() && timedOut -> Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "No peers found.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "Make sure both devices are on the same Wi-Fi network and that " +
                    "LocalSend is open on the receiver.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onClose) { Text("Close") }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = onRetry) { Text("Try again") }
            }
        }
        else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
            // currentFileName is the wire path (e.g. "2026-04-28-s-0004/photos/foo.jpg").
            // Showing it lets users see which bundle's files are flowing right now.
            Text(
                text = p.currentFileName ?: "…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun DoneBlock(state: SendUiState.Done, onDone: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        val (message, color) = when (val result = state.result) {
            SendBundleResult.Success ->
                "All files transferred — find them in the receiver's downloads folder." to
                    MaterialTheme.colorScheme.onSurface
            SendBundleResult.AlreadyReceived ->
                "Receiver reported the files were already there. Nothing to do." to
                    MaterialTheme.colorScheme.onSurfaceVariant
            is SendBundleResult.Failed ->
                result.message to MaterialTheme.colorScheme.error
        }
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
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
