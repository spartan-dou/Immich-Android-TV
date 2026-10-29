package nl.giejay.android.tv.immich.shared.prefs

import nl.giejay.android.tv.immich.api.model.Asset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class PhotosOrderTest {

    private fun asset(id: String, fileName: String?, favorite: Boolean = false, modified: Long = 0) = Asset(
        id = id,
        type = "IMAGE",
        deviceAssetId = null,
        exifInfo = null,
        fileModifiedAt = Date(modified),
        albumName = null,
        people = null,
        tags = null,
        originalPath = null,
        originalFileName = fileName,
        isFavorite = favorite
    )

    private val assets = listOf(
        asset("1", "b.jpg", modified = 1000),
        asset("2", "A.jpg", favorite = true, modified = 2000),
        asset("3", "c.jpg", modified = 3000),
        asset("4", null, favorite = true, modified = 4000)
    )

    @Test
    fun fileNameOrdersIgnoreCase() {
        assertEquals(listOf("4", "2", "1", "3"), assets.sortedWith(PhotosOrder.FILENAME_A_Z.sort).map { it.id })
        assertEquals(listOf("3", "1", "2", "4"), assets.sortedWith(PhotosOrder.FILENAME_Z_A.sort).map { it.id })
    }

    @Test
    fun favoritesFirstThenNewest() {
        assertEquals(listOf("4", "2", "3", "1"), assets.sortedWith(PhotosOrder.FAVORITES_FIRST.sort).map { it.id })
    }

    @Test
    fun randomOrderIsStableWithinASession() {
        val once = assets.sortedWith(PhotosOrder.RANDOM.sort).map { it.id }
        assertEquals(once, assets.reversed().sortedWith(PhotosOrder.RANDOM.sort).map { it.id })
    }

    @Test
    fun onlyDateOrdersAreOfferedForTheWholeLibrary() {
        assertTrue(PhotosOrder.dateOrders.all { it.isDateOrder })
        assertEquals(setOf(PhotosOrder.NEWEST_OLDEST, PhotosOrder.OLDEST_NEWEST), PhotosOrder.dateOrders.toSet())
        assertFalse(PhotosOrder.RANDOM.isDateOrder)
    }
}
