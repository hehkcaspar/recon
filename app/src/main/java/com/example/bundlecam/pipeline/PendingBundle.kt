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
)

@Serializable
data class PendingPhoto(
    val localPath: String,
    val rotationDegrees: Int,
)
