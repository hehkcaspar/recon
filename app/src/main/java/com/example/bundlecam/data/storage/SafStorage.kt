package com.example.bundlecam.data.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class SafStorage(context: Context) {
    private val appContext: Context = context.applicationContext

    suspend fun ensureBundleFolders(rootUri: Uri) = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(appContext, rootUri)
            ?: throw IOException("Unable to open tree: $rootUri")
        listOf(StorageLayout.BUNDLES_DIR, StorageLayout.STITCHED_DIR).forEach { name ->
            findOrCreateDir(root, name)
        }
    }

    suspend fun copyLocalFile(
        rootUri: Uri,
        subPath: List<String>,
        fileName: String,
        src: File,
    ): Uri = withContext(Dispatchers.IO) {
        val dir = resolveTargetDir(rootUri, subPath)
        writeOne(dir, indexExisting(dir), fileName, src)
    }

    suspend fun copyLocalFiles(
        rootUri: Uri,
        subPath: List<String>,
        entries: List<Pair<String, File>>,
    ): List<Uri> = withContext(Dispatchers.IO) {
        val dir = resolveTargetDir(rootUri, subPath)
        val existing = indexExisting(dir)
        entries.map { (fileName, src) -> writeOne(dir, existing, fileName, src) }
    }

    /**
     * One `listFiles()` IPC per batch instead of one `findFile()` per entry — findFile
     * rescans the entire directory on each call, so 50 photos previously meant 50 full
     * listings. Map is mutated as we overwrite so subsequent entries in the same batch
     * don't re-find a just-deleted stale file.
     */
    private fun indexExisting(dir: DocumentFile): MutableMap<String, DocumentFile> {
        val map = mutableMapOf<String, DocumentFile>()
        dir.listFiles().forEach { f -> f.name?.let { map[it] = f } }
        return map
    }

    private fun writeOne(
        dir: DocumentFile,
        existing: MutableMap<String, DocumentFile>,
        fileName: String,
        src: File,
    ): Uri {
        // Overwrite any stale file with the same name; DocumentsProvider would otherwise
        // append " (1)" suffixes, producing duplicates like "{id}-p-01 (1).jpg" on worker retry.
        existing.remove(fileName)?.delete()
        val file = dir.createFile(StorageLayout.mimeFor(fileName), fileName)
            ?: throw IOException("Failed to create file '$fileName'")
        appContext.contentResolver.openOutputStream(file.uri)?.use { out ->
            src.inputStream().use { it.copyTo(out, bufferSize = 1 shl 16) }
        } ?: throw IOException("Failed to open output stream for $fileName")
        return file.uri
    }

    private fun resolveTargetDir(rootUri: Uri, subPath: List<String>): DocumentFile {
        val root = DocumentFile.fromTreeUri(appContext, rootUri)
            ?: throw IOException("Unable to open tree: $rootUri")
        var dir = root
        for (segment in subPath) {
            dir = findOrCreateDir(dir, segment)
        }
        return dir
    }

    /**
     * Idempotent directory resolution for SAF trees.
     *
     * `createDirectory` can return null even when the caller has the right permissions:
     *   - a concurrent creator (e.g. `ensureBundleFolders` running in parallel with a worker
     *     after the user just switched output folders) materializes the same name between
     *     our `findFile` and our `createDirectory` call, and the DocumentsProvider rejects
     *     the duplicate;
     *   - transient provider glitches on some OEM implementations.
     *
     * Re-check with `findFile` after a null create before giving up, so a lost race becomes
     * a harmless no-op instead of a user-visible "Failed to create directory" failure.
     */
    private fun findOrCreateDir(parent: DocumentFile, name: String): DocumentFile {
        parent.findFile(name)?.takeIf { it.isDirectory }?.let { return it }
        parent.createDirectory(name)?.let { return it }
        parent.findFile(name)?.takeIf { it.isDirectory }?.let { return it }
        throw IOException("Failed to create directory '$name'")
    }
}
