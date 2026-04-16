package com.example.bundlecam.ui.setup

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.bundlecam.ui.common.rememberFolderPickerLauncher

@Composable
fun FolderPickerScreen(
    onFolderPicked: (Uri) -> Unit,
    modifier: Modifier = Modifier,
) {
    val launch = rememberFolderPickerLauncher(onFolderPicked)
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Recon",
            style = MaterialTheme.typography.headlineLarge,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Pick a folder where every bundle you capture will be saved. You can change this later in Settings.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = launch) {
            Text("Choose folder")
        }
    }
}
