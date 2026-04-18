package com.example.bundlecam.pipeline

import kotlinx.serialization.Serializable

@Serializable
data class PendingBundle(
    val bundleId: String,
    val rootUriString: String,
    val stitchQuality: String,
    val sessionId: String,
    val orderedPhotos: List<PendingPhoto>,
    val capturedAt: Long,
    // Defaults cover pre-flag manifests on disk at upgrade; post-upgrade writes always
    // carry explicit values (frozen at commit from SettingsState).
    val saveIndividualPhotos: Boolean = true,
    val saveStitchedImage: Boolean = true,
)

@Serializable
data class PendingPhoto(
    val localPath: String,
    val rotationDegrees: Int,
)
