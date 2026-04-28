package com.example.bundlecam.data.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

private const val TAG = "Recon/BundleLibrary"
private const val MAX_PREVIEW_THUMBNAILS = 3

/**
 * Reads bundles back out of the user's SAF-picked output folder for the in-app
 * Bundle Preview screen. SAF is an IPC surface — every `listFiles()` is a round-trip
 * to the DocumentsProvider — so per-bundle listings run in parallel. Only writes
 * happen through [SafStorage]; this class is read + delete.
 */
class BundleLibrary(context: Context) {
    private val appContext: Context = context.applicationContext

    suspend fun listBundles(rootUri: Uri): List<CompletedBundle> = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(appContext, rootUri) ?: return@withContext emptyList()

        val bundlesDir = root.findFile(StorageLayout.BUNDLES_DIR)?.takeIf { it.isDirectory }
        val stitchedDir = root.findFile(StorageLayout.STITCHED_DIR)?.takeIf { it.isDirectory }

        val bundleEntries = coroutineScope {
            bundlesDir?.listFiles().orEmpty()
                .filter { it.isDirectory }
                .map { dir ->
                    async {
                        val id = dir.name ?: return@async null
                        // Prefer the nested `photos/` subdir (Phase B+); fall back to the
                        // flat layout for legacy bundles written before the Phase B cutover.
                        val photosSource =
                            dir.findFile(StorageLayout.PHOTOS_SUBDIR)?.takeIf { it.isDirectory }
                                ?: dir
                        val photos = photosSource.mediaChildren(StorageLayout.MediaKind.Photo)
                        val videos = dir.findFile(StorageLayout.VIDEOS_SUBDIR)
                            ?.takeIf { it.isDirectory }
                            ?.mediaChildren(StorageLayout.MediaKind.Video)
                            .orEmpty()
                        val voices = dir.findFile(StorageLayout.AUDIO_SUBDIR)
                            ?.takeIf { it.isDirectory }
                            ?.mediaChildren(StorageLayout.MediaKind.Voice)
                            .orEmpty()
                        BundleDirEntry(
                            id = id,
                            subfolder = dir,
                            photoCount = photos.size,
                            videoCount = videos.size,
                            voiceCount = voices.size,
                            thumbnailPhotos = photos.take(MAX_PREVIEW_THUMBNAILS),
                            thumbnailVideos = videos.take(MAX_PREVIEW_THUMBNAILS),
                            thumbnailVoices = voices.take(MAX_PREVIEW_THUMBNAILS),
                        )
                    }
                }
                .awaitAll()
                .filterNotNull()
        }

        val stitchById = stitchedDir?.listFiles().orEmpty()
            .filter { it.isFile }
            .mapNotNull { file ->
                val name = file.name ?: return@mapNotNull null
                if (!name.endsWith(StorageLayout.STITCH_SUFFIX, ignoreCase = true)) return@mapNotNull null
                name.removeSuffix(StorageLayout.STITCH_SUFFIX) to file
            }
            .toMap()

        val ids = bundleEntries.mapTo(mutableSetOf()) { it.id } + stitchById.keys
        val bundlesById = bundleEntries.associateBy { it.id }

        ids.map { id ->
            val dirEntry = bundlesById[id]
            val stitch = stitchById[id]
            val modalities = buildList {
                if (dirEntry != null) add(BundleModality.Subfolder)
                if (stitch != null) add(BundleModality.Stitch)
            }
            // Thumbnail priority: photos first (proper visuals crop cleanly), then
            // video poster frames (extracted by the thumbnail decoder on demand),
            // then voice placeholders (synthetic navy tile with mic glyph). Fall
            // back to the stitched image only when no raw content exists.
            val thumbnailUris = when {
                dirEntry == null -> listOfNotNull(stitch?.uri)
                dirEntry.thumbnailPhotos.isNotEmpty() ->
                    dirEntry.thumbnailPhotos.map { it.uri }
                dirEntry.thumbnailVideos.isNotEmpty() ->
                    dirEntry.thumbnailVideos.map { it.uri }
                dirEntry.thumbnailVoices.isNotEmpty() ->
                    dirEntry.thumbnailVoices.map { it.uri }
                stitch != null -> listOf(stitch.uri)
                else -> emptyList()
            }
            CompletedBundle(
                id = id,
                modalities = modalities,
                subfolderUri = dirEntry?.subfolder?.uri,
                stitchUri = stitch?.uri,
                thumbnailUris = thumbnailUris,
                photoCount = dirEntry?.photoCount ?: 0,
                videoCount = dirEntry?.videoCount ?: 0,
                voiceCount = dirEntry?.voiceCount ?: 0,
            )
        }
            .filter { it.modalities.isNotEmpty() }
            // Newest first — matches how a user thinks about "my last capture".
            .sortedByDescending { it.id }
    }

    /**
     * Delete every modality for [bundle]. Uses [DocumentsContract.deleteDocument]
     * directly so the child-of-tree subfolder URI is deleted via the same code path as
     * a leaf file — [DocumentFile.fromTreeUri] is for tree roots and its fallback
     * `fromSingleUri` won't recursively delete a directory's contents. Best-effort:
     * partial failure is reported but doesn't resurrect the row.
     */
    /**
     * Enumerate every shippable file in [bundle] (per-modality subfolder contents +
     * stitched composite). Order: photos → videos → voice → stitch, lexicographic
     * within each modality so receivers see a stable order. Skips non-media files and
     * files with zero length (shouldn't occur, but defends against torn writes).
     *
     * Used by the LocalSend sender — sender treats one bundle as the atomic transfer
     * unit, and this is the single point that decides what counts as "every byte of
     * the bundle". Forward-compatible with future `.md` sidecars (BACKLOG item 1):
     * if the worker writes one alongside, it'll fall through to the bundle subfolder
     * branch and be picked up automatically.
     */
    suspend fun listBundleFiles(bundle: CompletedBundle): List<BundleFile> = withContext(Dispatchers.IO) {
        val files = mutableListOf<BundleFile>()

        bundle.subfolderUri?.let { subfolderUri ->
            val sub = DocumentFile.fromTreeUri(appContext, subfolderUri)
            if (sub != null && sub.isDirectory) {
                listOf(
                    StorageLayout.PHOTOS_SUBDIR,
                    StorageLayout.VIDEOS_SUBDIR,
                    StorageLayout.AUDIO_SUBDIR,
                ).forEach { subdirName ->
                    sub.findFile(subdirName)?.takeIf { it.isDirectory }?.let { dir ->
                        dir.listFiles()
                            .filter { it.isFile }
                            .sortedBy { it.name.orEmpty() }
                            .forEach { f ->
                                val name = f.name ?: return@forEach
                                if (StorageLayout.mediaKindFor(name) == null) return@forEach
                                val length = f.length()
                                if (length <= 0L) return@forEach
                                files += BundleFile(
                                    uri = f.uri,
                                    fileName = name,
                                    size = length,
                                    mimeType = StorageLayout.mimeFor(name),
                                )
                            }
                    }
                }
                // Legacy flat layout (pre-Phase B photo-only bundles): pick up any
                // media files directly under the bundle subfolder. Non-media + non-
                // length-bearing entries are skipped so the per-modality subdirs we
                // already iterated don't double-list.
                sub.listFiles()
                    .filter { it.isFile }
                    .sortedBy { it.name.orEmpty() }
                    .forEach { f ->
                        val name = f.name ?: return@forEach
                        if (StorageLayout.mediaKindFor(name) == null) return@forEach
                        val length = f.length()
                        if (length <= 0L) return@forEach
                        files += BundleFile(
                            uri = f.uri,
                            fileName = name,
                            size = length,
                            mimeType = StorageLayout.mimeFor(name),
                        )
                    }
            }
        }

        bundle.stitchUri?.let { stitchUri ->
            val stitchFile = DocumentFile.fromSingleUri(appContext, stitchUri)
            val name = stitchFile?.name ?: StorageLayout.stitchFileName(bundle.id)
            val size = stitchFile?.length() ?: 0L
            if (size > 0L) {
                files += BundleFile(
                    uri = stitchUri,
                    fileName = name,
                    size = size,
                    mimeType = StorageLayout.MIME_JPEG,
                )
            }
        }

        files
    }

    suspend fun deleteBundle(bundle: CompletedBundle): DeleteResult = withContext(Dispatchers.IO) {
        val resolver = appContext.contentResolver
        var deletedAny = false
        val failures = mutableListOf<String>()

        fun tryDelete(uri: Uri, label: String) {
            val ok = runCatching { DocumentsContract.deleteDocument(resolver, uri) }
                .onFailure { Log.w(TAG, "Failed to delete $label for ${bundle.id}", it) }
                .getOrDefault(false)
            if (ok) deletedAny = true else failures += label
        }

        bundle.subfolderUri?.let { tryDelete(it, "subfolder") }
        bundle.stitchUri?.let { tryDelete(it, "stitch") }

        when {
            failures.isEmpty() -> DeleteResult.Ok
            deletedAny -> DeleteResult.Partial(failures)
            else -> DeleteResult.Failed(failures)
        }
    }

    private data class BundleDirEntry(
        val id: String,
        val subfolder: DocumentFile,
        val photoCount: Int,
        val videoCount: Int,
        val voiceCount: Int,
        val thumbnailPhotos: List<DocumentFile>,
        val thumbnailVideos: List<DocumentFile>,
        val thumbnailVoices: List<DocumentFile>,
    )
}

sealed class DeleteResult {
    object Ok : DeleteResult()
    data class Partial(val failedParts: List<String>) : DeleteResult()
    data class Failed(val failedParts: List<String>) : DeleteResult()
}

/** Files of [kind] inside this directory, sorted lexicographically. */
private fun DocumentFile.mediaChildren(kind: StorageLayout.MediaKind): List<DocumentFile> =
    listFiles()
        .filter { it.isFile && StorageLayout.mediaKindFor(it.name.orEmpty()) == kind }
        .sortedBy { it.name }
