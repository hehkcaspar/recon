package com.example.bundlecam.pipeline

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import java.io.File
import java.io.IOException

private const val TAG = "Recon/ManifestStore"

class ManifestStore(context: Context) {
    private val dir: File =
        File(context.applicationContext.filesDir, "pending").apply { mkdirs() }

    // `classDiscriminator = "type"` makes each `PendingItem` encode with a `"type": "..."`
    // field derived from `@SerialName`. Legacy v1 manifests have no such field on their
    // photo entries — those are handled by explicit branching in `load()`, not by the
    // serializer (polymorphic-with-default is fragile across future subclass additions).
    private val json = Json {
        ignoreUnknownKeys = true
        // Encode default-valued fields (version, saveIndividualPhotos, saveStitchedImage).
        // Without this, a v2-shaped bundle with `version = 2` default writes JSON lacking
        // the `version` key, and on the next read the decoder falls through the v2 branch
        // (version defaulted to 1) and tries to decode via legacy v1, failing on the
        // missing `orderedPhotos` field.
        encodeDefaults = true
        classDiscriminator = "type"
        serializersModule = SerializersModule {
            polymorphic(PendingItem::class) {
                subclass(PendingPhoto::class)
                subclass(PendingVideo::class)
            }
        }
    }

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
        runCatching {
            val root = json.parseToJsonElement(file.readText()).jsonObject
            val version = root["version"]?.jsonPrimitive?.intOrNull ?: 1
            when (version) {
                1 -> decodeV1(root)
                2 -> json.decodeFromJsonElement(PendingBundle.serializer(), root)
                else -> {
                    Log.e(TAG, "Unknown manifest version $version for $bundleId; refusing to decode")
                    null
                }
            }
        }
            .onFailure { Log.e(TAG, "Manifest $bundleId failed to decode", it) }
            .getOrNull()
    }

    // Legacy v1 decode: the field was `orderedPhotos: List<PendingPhoto>` (no type tag).
    // Read each entry as a plain PendingPhoto and wrap into the v2 `orderedItems` shape.
    // Missing flags default to true (matches the pre-version-field behavior).
    private fun decodeV1(root: JsonObject): PendingBundle {
        val items = root["orderedPhotos"]?.jsonArray?.map { el ->
            val obj = el.jsonObject
            PendingPhoto(
                localPath = obj.getValue("localPath").jsonPrimitive.content,
                rotationDegrees = obj.getValue("rotationDegrees").jsonPrimitive.int,
            )
        } ?: emptyList()
        return PendingBundle(
            version = 2,
            bundleId = root.getValue("bundleId").jsonPrimitive.content,
            rootUriString = root.getValue("rootUriString").jsonPrimitive.content,
            stitchQuality = root.getValue("stitchQuality").jsonPrimitive.content,
            sessionId = root.getValue("sessionId").jsonPrimitive.content,
            orderedItems = items,
            capturedAt = root.getValue("capturedAt").jsonPrimitive.long,
            saveIndividualPhotos = root["saveIndividualPhotos"]?.jsonPrimitive?.boolean ?: true,
            saveStitchedImage = root["saveStitchedImage"]?.jsonPrimitive?.boolean ?: true,
        )
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
                val loaded = load(bundleId)
                if (loaded == null) {
                    // Either the file vanished or decode failed. Fail closed: keep staging
                    // so a retryable worker can still run.
                    Log.w(TAG, "Manifest $bundleId unreadable; assuming session $sessionId referenced")
                    return@withContext true
                }
                if (loaded.sessionId == sessionId) return@withContext true
            }
            false
        }
}
