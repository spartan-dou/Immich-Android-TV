package nl.giejay.android.tv.immich.shared.util

import nl.giejay.android.tv.immich.api.model.Asset
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssetRawPhotoTest {

    private fun asset(fileName: String?, path: String? = null) = Asset(
        id = "id",
        type = "IMAGE",
        deviceAssetId = null,
        exifInfo = null,
        fileModifiedAt = null,
        albumName = null,
        people = null,
        tags = null,
        originalPath = path,
        originalFileName = fileName
    )

    @Test
    fun detectsRawExtensionsCaseInsensitively() {
        listOf("IMG_0001.DNG", "a.cr2", "a.CR3", "a.nef", "a.arw", "a.RAF", "a.rw2").forEach {
            assertTrue(it, asset(it).isRawPhoto())
        }
    }

    @Test
    fun keepsRegularPictures() {
        listOf("IMG_0001.JPG", "a.heic", "a.png", "a.webp", "no_extension", "archive.raw.jpg").forEach {
            assertFalse(it, asset(it).isRawPhoto())
        }
    }

    @Test
    fun fallsBackToOriginalPath() {
        assertTrue(asset(null, "/library/2026/IMG_0001.dng").isRawPhoto())
        assertFalse(asset(null, null).isRawPhoto())
    }
}
