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
    // Defaults keep the pre-Phase-D read sites compiling unchanged. `BundleModality`
    // stays at {Subfolder, Stitch} — `Subfolder` already generalizes to "any raw
    // modality content under bundles/{id}/", and the per-modality count fields surface
    // the detail for Bundle Preview row rendering.
    val videoCount: Int = 0,
    val voiceCount: Int = 0,
)

enum class BundleModality {
    /** Raw JPEGs in `bundles/{id}/`. */
    Subfolder,
    /** Single vertically-stitched JPEG in `stitched/{id}-stitch.jpg`. */
    Stitch,
}
