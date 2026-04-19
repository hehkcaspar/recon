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
                        val photos = dir.listFiles()
                            .filter { it.isFile && it.name?.endsWith(".jpg", ignoreCase = true) == true }
                            .sortedBy { it.name }
                        BundleDirEntry(
                            id = id,
                            subfolder = dir,
                            photoCount = photos.size,
                            thumbnailPhotos = photos.take(MAX_PREVIEW_THUMBNAILS),
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
            val thumbnailUris = when {
                dirEntry != null && dirEntry.thumbnailPhotos.isNotEmpty() ->
                    dirEntry.thumbnailPhotos.map { it.uri }
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
        val thumbnailPhotos: List<DocumentFile>,
    )
}

sealed class DeleteResult {
    object Ok : DeleteResult()
    data class Partial(val failedParts: List<String>) : DeleteResult()
    data class Failed(val failedParts: List<String>) : DeleteResult()
}
