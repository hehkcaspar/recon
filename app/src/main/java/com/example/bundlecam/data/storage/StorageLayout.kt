package com.example.bundlecam.data.storage

object StorageLayout {
    const val BUNDLES_DIR = "bundles"
    const val STITCHED_DIR = "stitched"
    const val MIME_JPEG = "image/jpeg"

    private const val BUNDLE_ID_FORMAT = "%s-s-%04d"
    private const val BUNDLE_PHOTO_FORMAT = "%s-p-%02d.jpg"
    private const val STITCH_FILE_FORMAT = "%s-stitch.jpg"

    fun bundleId(date: String, counter: Int): String = BUNDLE_ID_FORMAT.format(date, counter)
    fun bundlePhotoName(bundleId: String, oneBasedIndex: Int): String =
        BUNDLE_PHOTO_FORMAT.format(bundleId, oneBasedIndex)
    fun stitchFileName(bundleId: String): String = STITCH_FILE_FORMAT.format(bundleId)
    fun bundlePath(bundleId: String): List<String> = listOf(BUNDLES_DIR, bundleId)
    val STITCHED_PATH: List<String> = listOf(STITCHED_DIR)

    fun photoExifComment(bundleId: String, oneBasedIndex: Int): String =
        "BundleCam:$bundleId:p${"%02d".format(oneBasedIndex)}"

    fun stitchExifComment(bundleId: String): String =
        "BundleCam:$bundleId:stitch"
}
