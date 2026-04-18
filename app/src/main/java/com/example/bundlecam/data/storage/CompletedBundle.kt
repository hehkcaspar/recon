package com.example.bundlecam.data.storage

import android.net.Uri

/**
 * One bundle as discovered on the user's SAF-picked output folder.
 *
 * A bundle is considered present if *either* modality exists — we don't require both,
 * because the user may have toggled one off when this bundle was captured.
 */
data class CompletedBundle(
    val id: String,
    val modalities: List<BundleModality>,
    val subfolderUri: Uri?,
    val stitchUri: Uri?,
    /**
     * Up to 3 preview URIs, in display order. Prefers raw photos from the subfolder
     * (they crop cleanly) and falls back to the stitched image only when no subfolder
     * was kept — the stitched JPEG is tall and looks poor in a square thumbnail.
     */
    val thumbnailUris: List<Uri>,
    val photoCount: Int,
)

enum class BundleModality {
    /** Raw JPEGs in `bundles/{id}/`. */
    Subfolder,
    /** Single vertically-stitched JPEG in `stitched/{id}-stitch.jpg`. */
    Stitch,
}
