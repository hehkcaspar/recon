package com.example.bundlecam.data.storage

object StorageLayout {
    const val BUNDLES_DIR = "bundles"
    const val STITCHED_DIR = "stitched"

    // Per-modality subdirectories inside a bundle. Multimodal bundles nest into these;
    // legacy photo-only bundles predating Phase B live flat at `bundles/{id}/*.jpg`, and
    // `BundleLibrary` reads both formats.
    const val PHOTOS_SUBDIR = "photos"
    const val VIDEOS_SUBDIR = "videos"
    const val AUDIO_SUBDIR = "audio"

    const val EXT_JPG = "jpg"
    const val EXT_JPEG = "jpeg"
    const val EXT_MP4 = "mp4"
    const val EXT_M4A = "m4a"

    const val MIME_JPEG = "image/jpeg"
    const val MIME_MP4 = "video/mp4"
    const val MIME_M4A = "audio/mp4"
    const val MIME_OCTET_STREAM = "application/octet-stream"

    /** The filename tail for stitched outputs — shared with [BundleLibrary] when parsing names back. */
    const val STITCH_SUFFIX = "-stitch.jpg"

    private const val BUNDLE_ID_FORMAT = "%s-s-%04d"
    // 3-digit zero-pad: sorts lexicographically up to 999 items per bundle. The previous
    // 2-digit format broke sort order past 99 (p-100 would come before p-09).
    private const val BUNDLE_PHOTO_FORMAT = "%s-p-%03d.jpg"
    private const val BUNDLE_VIDEO_FORMAT = "%s-v-%03d.mp4"
    private const val BUNDLE_AUDIO_FORMAT = "%s-a-%03d.m4a"
    private const val STITCH_FILE_FORMAT = "%s$STITCH_SUFFIX"

    fun bundleId(date: String, counter: Int): String = BUNDLE_ID_FORMAT.format(date, counter)
    fun bundlePhotoName(bundleId: String, oneBasedIndex: Int): String =
        BUNDLE_PHOTO_FORMAT.format(bundleId, oneBasedIndex)
    fun bundleVideoName(bundleId: String, oneBasedIndex: Int): String =
        BUNDLE_VIDEO_FORMAT.format(bundleId, oneBasedIndex)
    fun bundleAudioName(bundleId: String, oneBasedIndex: Int): String =
        BUNDLE_AUDIO_FORMAT.format(bundleId, oneBasedIndex)
    fun stitchFileName(bundleId: String): String = STITCH_FILE_FORMAT.format(bundleId)

    /** The bundle's top-level directory. Used for delete + folder enumeration. */
    fun bundlePath(bundleId: String): List<String> = listOf(BUNDLES_DIR, bundleId)
    /** The per-modality subfolders inside a bundle. Writers target these; reads prefer these and fall back to flat. */
    fun bundlePhotosPath(bundleId: String): List<String> = listOf(BUNDLES_DIR, bundleId, PHOTOS_SUBDIR)
    fun bundleVideosPath(bundleId: String): List<String> = listOf(BUNDLES_DIR, bundleId, VIDEOS_SUBDIR)
    fun bundleAudioPath(bundleId: String): List<String> = listOf(BUNDLES_DIR, bundleId, AUDIO_SUBDIR)
    val STITCHED_PATH: List<String> = listOf(STITCHED_DIR)

    fun photoExifComment(bundleId: String, oneBasedIndex: Int): String =
        "Recon:$bundleId:p${"%03d".format(oneBasedIndex)}"

    fun stitchExifComment(bundleId: String): String =
        "Recon:$bundleId:stitch"

    /**
     * Derive the MIME type from a filename extension. `SafStorage.writeOne` needs this
     * because `DocumentFile.createFile(mime, name)` is mime-aware on strict
     * DocumentsProviders (Drive rejects a `video/mp4` filename created with `image/jpeg`).
     */
    fun mimeFor(fileName: String): String = when (mediaKindFor(fileName)) {
        MediaKind.Photo -> MIME_JPEG
        MediaKind.Video -> MIME_MP4
        MediaKind.Voice -> MIME_M4A
        null -> MIME_OCTET_STREAM
    }

    enum class MediaKind { Photo, Video, Voice }

    /** Classify a filename (or bare extension) into one of the three capture modalities. */
    fun mediaKindFor(fileName: String): MediaKind? {
        val ext = fileName.substringAfterLast('.', fileName).lowercase()
        return when (ext) {
            EXT_JPG, EXT_JPEG -> MediaKind.Photo
            EXT_MP4 -> MediaKind.Video
            EXT_M4A -> MediaKind.Voice
            else -> null
        }
    }
}
