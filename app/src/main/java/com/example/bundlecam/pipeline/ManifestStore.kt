package com.example.bundlecam.pipeline

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

private const val TAG = "Recon/ManifestStore"

class ManifestStore(context: Context) {
    private val dir: File =
        File(context.applicationContext.filesDir, "pending").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun save(manifest: PendingBundle) = withContext(Dispatchers.IO) {
        val file = File(dir, "${manifest.bundleId}.json")
        val payload = json.encodeToString(PendingBundle.serializer(), manifest)
        file.writeText(payload)
        if (!file.exists()) {
            throw IOException("Manifest write did not persist: ${file.absolutePath}")
        }
        val written = file.length()
        Log.i(TAG, "Saved manifest ${manifest.bundleId} at ${file.absolutePath} ($written bytes)")
    }

    suspend fun load(bundleId: String): PendingBundle? = withContext(Dispatchers.IO) {
        val file = File(dir, "$bundleId.json")
        if (!file.exists()) {
            val siblings = dir.listFiles()?.joinToString { it.name } ?: "(listFiles null)"
            Log.w(TAG, "Manifest missing on disk: ${file.absolutePath} — siblings in dir: [$siblings]")
            return@withContext null
        }
        runCatching { json.decodeFromString(PendingBundle.serializer(), file.readText()) }
            .onFailure { Log.e(TAG, "Manifest $bundleId failed to decode", it) }
            .getOrNull()
    }

    suspend fun delete(bundleId: String) = withContext(Dispatchers.IO) {
        val file = File(dir, "$bundleId.json")
        val deleted = file.delete()
        Log.i(TAG, "Delete manifest $bundleId → $deleted (exists=${file.exists()})")
    }

    suspend fun listPendingIds(): List<String> = withContext(Dispatchers.IO) {
        dir.listFiles { f -> f.extension == "json" }
            ?.map { it.nameWithoutExtension }
            ?: emptyList()
    }

    // Whether any pending manifest (other than excludeBundleId) references the given session.
    // Fails closed on decode errors: an unreadable manifest is treated as a live reference
    // so we don't delete staging a retryable worker still needs.
    suspend fun isSessionReferenced(sessionId: String, excludeBundleId: String? = null): Boolean =
        withContext(Dispatchers.IO) {
            for (bundleId in listPendingIds()) {
                if (bundleId == excludeBundleId) continue
                val file = File(dir, "$bundleId.json")
                if (!file.exists()) continue
                val decoded = runCatching {
                    json.decodeFromString(PendingBundle.serializer(), file.readText())
                }
                if (decoded.isFailure) {
                    Log.w(TAG, "Manifest $bundleId unreadable; assuming session $sessionId referenced", decoded.exceptionOrNull())
                    return@withContext true
                }
                if (decoded.getOrNull()?.sessionId == sessionId) return@withContext true
            }
            false
        }
}
