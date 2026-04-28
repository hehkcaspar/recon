package com.example.bundlecam.data.storage

import android.net.Uri

/**
 * One file inside a [CompletedBundle], resolved for streaming. Used by the LocalSend
 * sender (and any other bundle-shipping path) to enumerate every byte that should be
 * transferred without re-walking the SAF tree per file.
 */
data class BundleFile(
    val uri: Uri,
    val fileName: String,
    val size: Long,
    val mimeType: String,
)
