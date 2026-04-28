package com.example.bundlecam

import android.net.Uri
import com.example.bundlecam.data.storage.BundleFile
import com.example.bundlecam.network.localsend.Info
import com.example.bundlecam.network.localsend.LocalSendUploader
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

    private fun fakeFile(name: String, size: Long, mime: String) = BundleFile(
        uri = stubUri,
        fileName = name,
        size = size,
        mimeType = mime,
    )

    private val info = Info(alias = "Sender", fingerprint = "fp")

    @Test
    fun maps_files_by_filename_as_id() {
        val files = listOf(
            fakeFile("2026-04-28-s-0001-p-001.jpg", 100L, "image/jpeg"),
            fakeFile("2026-04-28-s-0001-v-001.mp4", 200L, "video/mp4"),
        )
        val req = LocalSendUploader.buildPrepareUploadRequest(files, info)
        assertEquals(2, req.files.size)
        assertNotNull(req.files["2026-04-28-s-0001-p-001.jpg"])
        assertNotNull(req.files["2026-04-28-s-0001-v-001.mp4"])
        assertSame(info, req.info)
    }

    @Test
    fun preserves_input_order_via_linked_hashmap() {
        // LinkedHashMap preservation is a meaningful contract — receivers may surface
        // files in iteration order, and Recon's per-modality enumeration is the order
        // that matches what the user captured.
        val files = listOf(
            fakeFile("a.jpg", 1L, "image/jpeg"),
            fakeFile("b.mp4", 1L, "video/mp4"),
            fakeFile("c.m4a", 1L, "audio/mp4"),
            fakeFile("d-stitch.jpg", 1L, "image/jpeg"),
        )
        val req = LocalSendUploader.buildPrepareUploadRequest(files, info)
        val order = req.files.keys.toList()
        assertEquals(listOf("a.jpg", "b.mp4", "c.m4a", "d-stitch.jpg"), order)
    }

    @Test
    fun file_metadata_fields_match_input() {
        val f = fakeFile("v.mp4", 4242L, "video/mp4")
        val req = LocalSendUploader.buildPrepareUploadRequest(listOf(f), info)
        val meta = req.files["v.mp4"]!!
        assertEquals("v.mp4", meta.id)
        assertEquals("v.mp4", meta.fileName)
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

    @Test
    fun mixed_modalities_preserved() {
        val files = listOf(
            fakeFile("p.jpg", 1L, "image/jpeg"),
            fakeFile("v.mp4", 2L, "video/mp4"),
            fakeFile("a.m4a", 3L, "audio/mp4"),
        )
        val req = LocalSendUploader.buildPrepareUploadRequest(files, info)
        assertEquals("image/jpeg", req.files["p.jpg"]?.fileType)
        assertEquals("video/mp4", req.files["v.mp4"]?.fileType)
        assertEquals("audio/mp4", req.files["a.m4a"]?.fileType)
    }
}
