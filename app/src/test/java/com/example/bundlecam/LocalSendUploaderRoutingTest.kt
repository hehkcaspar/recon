package com.example.bundlecam

import android.net.Uri
import com.example.bundlecam.data.storage.BundleFile
import com.example.bundlecam.network.localsend.Info
import com.example.bundlecam.network.localsend.LocalSendUploader
import com.example.bundlecam.network.localsend.UploadItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.Mockito

class LocalSendUploaderRoutingTest {

    // The Android Uri stub from the mockable android.jar returns null on every call,
    // including the static initializer that builds Uri.EMPTY — which makes Uri.EMPTY null
    // and the BundleFile constructor's runtime null-check throws. Mockito gives us a
    // non-null instance the pure-function tests can pass through without dereferencing.
    private val stubUri: Uri = Mockito.mock(Uri::class.java)

    private fun bundleFile(name: String, size: Long, mime: String, subfolder: String) =
        BundleFile(
            uri = stubUri,
            fileName = name,
            size = size,
            mimeType = mime,
            subfolder = subfolder,
        )

    private fun item(bundleId: String, name: String, size: Long, mime: String, subfolder: String) =
        UploadItem(
            source = bundleFile(name, size, mime, subfolder),
            wireName = LocalSendUploader.wireNameFor(bundleId, bundleFile(name, size, mime, subfolder)),
        )

    private val info = Info(alias = "Sender", fingerprint = "fp")

    @Test
    fun wire_name_includes_bundle_id_and_subfolder() {
        val f = bundleFile("foo.jpg", 1L, "image/jpeg", "photos")
        assertEquals(
            "2026-04-28-s-0004/photos/foo.jpg",
            LocalSendUploader.wireNameFor("2026-04-28-s-0004", f),
        )
    }

    @Test
    fun wire_name_skips_subfolder_when_empty() {
        // Legacy flat-layout bundles have files at the bundle root; the wire path
        // should be `{bundleId}/{leaf}`, not `{bundleId}//{leaf}`.
        val f = bundleFile("legacy.jpg", 1L, "image/jpeg", "")
        assertEquals(
            "legacy-bundle/legacy.jpg",
            LocalSendUploader.wireNameFor("legacy-bundle", f),
        )
    }

    @Test
    fun stitch_lands_under_stitched_subfolder() {
        // BundleLibrary sets `subfolder = "stitched"` for the composite, so the receiver
        // creates `{bundleId}/stitched/{leaf}-stitch.jpg`.
        val f = bundleFile("2026-04-28-s-0001-stitch.jpg", 1L, "image/jpeg", "stitched")
        assertEquals(
            "2026-04-28-s-0001/stitched/2026-04-28-s-0001-stitch.jpg",
            LocalSendUploader.wireNameFor("2026-04-28-s-0001", f),
        )
    }

    @Test
    fun maps_items_by_wire_name_as_id() {
        val items = listOf(
            item("bundle-A", "p.jpg", 100L, "image/jpeg", "photos"),
            item("bundle-A", "v.mp4", 200L, "video/mp4", "videos"),
        )
        val req = LocalSendUploader.buildPrepareUploadRequest(items, info)
        assertEquals(2, req.files.size)
        assertNotNull(req.files["bundle-A/photos/p.jpg"])
        assertNotNull(req.files["bundle-A/videos/v.mp4"])
        assertSame(info, req.info)
    }

    @Test
    fun multi_bundle_session_carries_distinct_wire_paths() {
        // The single-session multi-bundle path is the whole point — verify two bundles'
        // worth of files coexist in one prepare-upload payload.
        val items = listOf(
            item("bundle-A", "p-001.jpg", 100L, "image/jpeg", "photos"),
            item("bundle-A", "p-002.jpg", 100L, "image/jpeg", "photos"),
            item("bundle-B", "p-001.jpg", 200L, "image/jpeg", "photos"),
            item("bundle-B", "v-001.mp4", 300L, "video/mp4", "videos"),
        )
        val req = LocalSendUploader.buildPrepareUploadRequest(items, info)
        assertEquals(4, req.files.size)
        assertNotNull(req.files["bundle-A/photos/p-001.jpg"])
        assertNotNull(req.files["bundle-A/photos/p-002.jpg"])
        assertNotNull(req.files["bundle-B/photos/p-001.jpg"])
        assertNotNull(req.files["bundle-B/videos/v-001.mp4"])
    }

    @Test
    fun preserves_input_order_via_linked_hashmap() {
        // LinkedHashMap preservation is a meaningful contract — receivers may surface
        // files in iteration order, and Recon's per-modality enumeration is the order
        // that matches what the user captured.
        val items = listOf(
            item("b", "a.jpg", 1L, "image/jpeg", "photos"),
            item("b", "b.mp4", 1L, "video/mp4", "videos"),
            item("b", "c.m4a", 1L, "audio/mp4", "audio"),
            item("b", "d-stitch.jpg", 1L, "image/jpeg", "stitched"),
        )
        val req = LocalSendUploader.buildPrepareUploadRequest(items, info)
        val order = req.files.keys.toList()
        assertEquals(
            listOf(
                "b/photos/a.jpg",
                "b/videos/b.mp4",
                "b/audio/c.m4a",
                "b/stitched/d-stitch.jpg",
            ),
            order,
        )
    }

    @Test
    fun file_metadata_fields_match_input() {
        val items = listOf(item("b", "v.mp4", 4242L, "video/mp4", "videos"))
        val req = LocalSendUploader.buildPrepareUploadRequest(items, info)
        val meta = req.files["b/videos/v.mp4"]!!
        assertEquals("b/videos/v.mp4", meta.id)
        assertEquals("b/videos/v.mp4", meta.fileName)
        assertEquals(4242L, meta.size)
        assertEquals("video/mp4", meta.fileType)
        assertNull(meta.sha256)
        assertNull(meta.preview)
        assertNull(meta.metadata)
    }

    @Test
    fun empty_input_produces_empty_files_map() {
        val req = LocalSendUploader.buildPrepareUploadRequest(emptyList(), info)
        assertEquals(0, req.files.size)
        assertSame(info, req.info)
    }
}
