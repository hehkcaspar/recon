package com.example.bundlecam

import com.example.bundlecam.pipeline.PendingBundle
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingBundleSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun legacyManifest_defaultsBothOutputFlagsToTrue() {
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

        val decoded = json.decodeFromString(PendingBundle.serializer(), legacy)

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
    fun roundTrip_preservesOutputFlags() {
        val original = PendingBundle(
            bundleId = "2026-04-14-s-0004",
            rootUriString = "content://stub",
            stitchQuality = "HIGH",
            sessionId = "sess-2",
            orderedPhotos = emptyList(),
            capturedAt = 1712000000000L,
            saveIndividualPhotos = true,
            saveStitchedImage = false,
        )

        val roundTripped = json.decodeFromString(
            PendingBundle.serializer(),
            json.encodeToString(PendingBundle.serializer(), original),
        )

        assertEquals(original, roundTripped)
    }
}
