package com.example.bundlecam

import com.example.bundlecam.pipeline.PendingBundle
import com.example.bundlecam.pipeline.PendingItem
import com.example.bundlecam.pipeline.PendingPhoto
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingBundleSerializationTest {

    // Mirrors the production ManifestStore config so tests cover the real encode path.
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
        serializersModule = SerializersModule {
            polymorphic(PendingItem::class) {
                subclass(PendingPhoto::class)
            }
        }
    }

    // Minimal hand-rolled v1→v2 decoder that mirrors ManifestStore.decodeV1. The production
    // decoder is inside ManifestStore (Android dep) so we replicate its logic here for a
    // pure-JVM test. If the production path diverges, these tests must be updated too.
    private fun decodeViaLoad(raw: String): PendingBundle? {
        val root = json.parseToJsonElement(raw).jsonObject
        val version = root["version"]?.jsonPrimitive?.intOrNull ?: 1
        return when (version) {
            1 -> decodeV1(root)
            2 -> json.decodeFromJsonElement(PendingBundle.serializer(), root)
            else -> null
        }
    }

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

    @Test
    fun legacyV1Manifest_emptyPhotos_decodesToEmptyOrderedItems() {
        val legacy = """
            {
              "bundleId": "2026-04-14-s-0003",
              "rootUriString": "content://stub",
              "stitchQuality": "STANDARD",
              "sessionId": "sess-1",
              "orderedPhotos": [],
              "capturedAt": 1712000000000
            }
        """.trimIndent()

        val decoded = decodeViaLoad(legacy)
        assertNotNull(decoded)
        assertEquals(2, decoded!!.version)
        assertTrue(decoded.orderedItems.isEmpty())
        assertTrue(
            "Legacy manifest must default saveIndividualPhotos=true",
            decoded.saveIndividualPhotos,
        )
        assertTrue(
            "Legacy manifest must default saveStitchedImage=true",
            decoded.saveStitchedImage,
        )
    }

    @Test
    fun legacyV1Manifest_withPhotos_wrapsAsPendingPhotoInstances() {
        val legacy = """
            {
              "bundleId": "2026-04-14-s-0004",
              "rootUriString": "content://stub",
              "stitchQuality": "HIGH",
              "sessionId": "sess-2",
              "orderedPhotos": [
                {"localPath": "/staging/a.jpg", "rotationDegrees": 0},
                {"localPath": "/staging/b.jpg", "rotationDegrees": 90}
              ],
              "capturedAt": 1712000000000,
              "saveIndividualPhotos": true,
              "saveStitchedImage": false
            }
        """.trimIndent()

        val decoded = decodeViaLoad(legacy)
        assertNotNull(decoded)
        assertEquals(2, decoded!!.orderedItems.size)
        val first = decoded.orderedItems[0] as PendingPhoto
        assertEquals("/staging/a.jpg", first.localPath)
        assertEquals(0, first.rotationDegrees)
        val second = decoded.orderedItems[1] as PendingPhoto
        assertEquals(90, second.rotationDegrees)
        assertTrue(decoded.saveIndividualPhotos)
        assertTrue(!decoded.saveStitchedImage)
    }

    @Test
    fun v2Manifest_encodeThenDecode_roundTrips() {
        val original = PendingBundle(
            version = 2,
            bundleId = "2026-04-14-s-0005",
            rootUriString = "content://stub",
            stitchQuality = "STANDARD",
            sessionId = "sess-3",
            orderedItems = listOf(
                PendingPhoto(localPath = "/staging/x.jpg", rotationDegrees = 0),
                PendingPhoto(localPath = "/staging/y.jpg", rotationDegrees = 270),
            ),
            capturedAt = 1712000500000L,
            saveIndividualPhotos = true,
            saveStitchedImage = true,
        )
        val encoded = json.encodeToString(PendingBundle.serializer(), original)
        val decoded = decodeViaLoad(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun v2Manifest_encodedJson_includesTypeDiscriminator() {
        val bundle = PendingBundle(
            version = 2,
            bundleId = "2026-04-14-s-0006",
            rootUriString = "content://stub",
            stitchQuality = "LOW",
            sessionId = "sess-4",
            orderedItems = listOf(
                PendingPhoto(localPath = "/staging/p.jpg", rotationDegrees = 180),
            ),
            capturedAt = 1712000600000L,
        )
        val encoded = json.encodeToString(PendingBundle.serializer(), bundle)
        val tree = json.parseToJsonElement(encoded).jsonObject
        assertEquals(2, tree["version"]!!.jsonPrimitive.int)
        val items = tree["orderedItems"]!!.jsonArray
        assertEquals(1, items.size)
        val firstItem = items[0].jsonObject
        // @SerialName("photo") ensures the discriminator is "photo"
        assertEquals("photo", firstItem["type"]!!.jsonPrimitive.content)
        assertEquals("/staging/p.jpg", firstItem["localPath"]!!.jsonPrimitive.content)
    }

    @Test
    fun unknownFutureVersion_returnsNull() {
        val future = """
            {
              "version": 99,
              "bundleId": "future",
              "rootUriString": "content://stub",
              "stitchQuality": "STANDARD",
              "sessionId": "sess-5",
              "orderedItems": [],
              "capturedAt": 1712000700000
            }
        """.trimIndent()
        assertNull(decodeViaLoad(future))
    }
}
