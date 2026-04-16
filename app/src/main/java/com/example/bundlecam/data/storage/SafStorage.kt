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
            if (root.findFile(name)?.isDirectory != true) {
                root.createDirectory(name)
                    ?: throw IOException("Failed to create directory '$name'")
            }
        }
    }

    suspend fun copyLocalFile(
        rootUri: Uri,
        subPath: List<String>,
        fileName: String,
        src: File,
    ): Uri = withContext(Dispatchers.IO) {
        val dir = resolveTargetDir(rootUri, subPath)
        writeOne(dir, fileName, src)
    }

    suspend fun copyLocalFiles(
        rootUri: Uri,
        subPath: List<String>,
        entries: List<Pair<String, File>>,
    ): List<Uri> = withContext(Dispatchers.IO) {
        val dir = resolveTargetDir(rootUri, subPath)
        entries.map { (fileName, src) -> writeOne(dir, fileName, src) }
    }

    private fun writeOne(dir: DocumentFile, fileName: String, src: File): Uri {
        val file = dir.createFile(StorageLayout.MIME_JPEG, fileName)
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
            dir = dir.findFile(segment)?.takeIf { it.isDirectory }
                ?: dir.createDirectory(segment)
                ?: throw IOException("Failed to create directory '$segment'")
        }
        return dir
    }
}
