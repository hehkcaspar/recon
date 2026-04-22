package com.example.bundlecam.pipeline

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PendingBundle(
    // On-disk schema version. 1 = legacy `orderedPhotos: List<PendingPhoto>` (no type
    // discriminator on items). 2 = `orderedItems: List<PendingItem>` with `@SerialName`
    // discriminators. `ManifestStore.load` branches on this field; encoding always writes 2.
    val version: Int = 2,
    val bundleId: String,
    val rootUriString: String,
    val stitchQuality: String,
    val sessionId: String,
    val orderedItems: List<PendingItem>,
    val capturedAt: Long,
    // Defaults cover pre-flag manifests on disk at upgrade; post-upgrade writes always
    // carry explicit values (frozen at commit from SettingsState).
    val saveIndividualPhotos: Boolean = true,
    val saveStitchedImage: Boolean = true,
)

@Serializable
sealed class PendingItem {
    abstract val localPath: String
}

@Serializable
@SerialName("photo")
data class PendingPhoto(
    override val localPath: String,
    val rotationDegrees: Int,
) : PendingItem()

@Serializable
@SerialName("video")
data class PendingVideo(
    override val localPath: String,
    val rotationDegrees: Int,
    val durationMs: Long,
) : PendingItem()

@Serializable
@SerialName("voice")
data class PendingVoice(
    override val localPath: String,
    val durationMs: Long,
) : PendingItem()
