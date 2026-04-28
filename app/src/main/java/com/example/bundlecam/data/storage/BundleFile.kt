package com.example.bundlecam.data.storage

import android.net.Uri

/**
 * One file inside a [CompletedBundle], resolved for streaming. Used by the LocalSend
 * sender (and any other bundle-shipping path) to enumerate every byte that should be
 * transferred without re-walking the SAF tree per file.
 *
 * @param subfolder where in the bundle this file lives — `"photos"`, `"videos"`,
 *     `"audio"`, `"stitched"`, or `""` for the bundle root (legacy flat-layout
 *     photos). The LocalSend sender uses this to construct the wire-side fileName as
 *     `{bundleId}/{subfolder}/{fileName}` so the receiver mirrors the bundle's
 *     internal layout.
 */
data class BundleFile(
    val uri: Uri,
    val fileName: String,
    val size: Long,
    val mimeType: String,
    val subfolder: String,
)
