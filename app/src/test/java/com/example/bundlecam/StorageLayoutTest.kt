package com.example.bundlecam

import com.example.bundlecam.data.storage.StorageLayout
import org.junit.Assert.assertEquals
import org.junit.Test

class StorageLayoutTest {

    @Test
    fun bundlePhotoName_zeroPadsToThreeDigits() {
        val id = "2026-04-14-s-0003"
        assertEquals("$id-p-001.jpg", StorageLayout.bundlePhotoName(id, 1))
        assertEquals("$id-p-099.jpg", StorageLayout.bundlePhotoName(id, 99))
        assertEquals("$id-p-100.jpg", StorageLayout.bundlePhotoName(id, 100))
        assertEquals("$id-p-999.jpg", StorageLayout.bundlePhotoName(id, 999))
    }

    @Test
    fun bundlePhotoNames_sortLexicographicallyUpTo999() {
        val id = "2026-04-14-s-0003"
        val names = listOf(1, 9, 10, 11, 99, 100, 101, 999).map { StorageLayout.bundlePhotoName(id, it) }
        // With %03d, alphabetical sort equals numerical sort. The old %02d broke this past 99.
        assertEquals(names.sortedBy { it }, names)
    }

    @Test
    fun bundleVideoName_zeroPadsToThreeDigits() {
        val id = "2026-04-14-s-0003"
        assertEquals("$id-v-001.mp4", StorageLayout.bundleVideoName(id, 1))
        assertEquals("$id-v-042.mp4", StorageLayout.bundleVideoName(id, 42))
    }

    @Test
    fun bundleAudioName_zeroPadsToThreeDigits() {
        val id = "2026-04-14-s-0003"
        assertEquals("$id-a-001.m4a", StorageLayout.bundleAudioName(id, 1))
        assertEquals("$id-a-007.m4a", StorageLayout.bundleAudioName(id, 7))
    }

    @Test
    fun bundlePhotosPath_nestsIntoPhotosSubdir() {
        assertEquals(
            listOf("bundles", "2026-04-14-s-0003", "photos"),
            StorageLayout.bundlePhotosPath("2026-04-14-s-0003"),
        )
    }

    @Test
    fun bundlePath_returnsJustBundleDir() {
        // Top-level bundle directory, no subdir suffix — used for delete + enumeration.
        assertEquals(
            listOf("bundles", "2026-04-14-s-0003"),
            StorageLayout.bundlePath("2026-04-14-s-0003"),
        )
    }

    @Test
    fun bundleVideosPath_nestsIntoVideosSubdir() {
        assertEquals(
            listOf("bundles", "2026-04-14-s-0003", "videos"),
            StorageLayout.bundleVideosPath("2026-04-14-s-0003"),
        )
    }

    @Test
    fun bundleAudioPath_nestsIntoAudioSubdir() {
        assertEquals(
            listOf("bundles", "2026-04-14-s-0003", "audio"),
            StorageLayout.bundleAudioPath("2026-04-14-s-0003"),
        )
    }

    @Test
    fun mimeFor_recognizesJpegVariants() {
        assertEquals("image/jpeg", StorageLayout.mimeFor("photo.jpg"))
        assertEquals("image/jpeg", StorageLayout.mimeFor("photo.JPG"))
        assertEquals("image/jpeg", StorageLayout.mimeFor("photo.jpeg"))
        assertEquals("image/jpeg", StorageLayout.mimeFor("a-b-c-stitch.jpg"))
    }

    @Test
    fun mimeFor_recognizesVideoAndAudio() {
        assertEquals("video/mp4", StorageLayout.mimeFor("clip.mp4"))
        assertEquals("video/mp4", StorageLayout.mimeFor("CLIP.MP4"))
        assertEquals("audio/mp4", StorageLayout.mimeFor("voice.m4a"))
    }

    @Test
    fun mimeFor_fallsBackToOctetStream_onUnknownExtension() {
        assertEquals("application/octet-stream", StorageLayout.mimeFor("mystery.bin"))
        assertEquals("application/octet-stream", StorageLayout.mimeFor("no_extension"))
        assertEquals("application/octet-stream", StorageLayout.mimeFor(""))
    }

    @Test
    fun photoExifComment_usesThreeDigitIndex() {
        assertEquals("Recon:2026-04-14-s-0003:p001", StorageLayout.photoExifComment("2026-04-14-s-0003", 1))
        assertEquals("Recon:2026-04-14-s-0003:p042", StorageLayout.photoExifComment("2026-04-14-s-0003", 42))
    }

    @Test
    fun bundleId_zeroPadsCounterToFour() {
        assertEquals("2026-04-14-s-0001", StorageLayout.bundleId("2026-04-14", 1))
        assertEquals("2026-04-14-s-9999", StorageLayout.bundleId("2026-04-14", 9999))
    }
}
