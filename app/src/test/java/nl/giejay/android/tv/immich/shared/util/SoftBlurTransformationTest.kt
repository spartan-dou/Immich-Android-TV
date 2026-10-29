package nl.giejay.android.tv.immich.shared.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SoftBlurTransformationTest {
    private val blur = SoftBlurTransformation(radius = 2)

    private fun gray(value: Int) = (0xff shl 24) or (value shl 16) or (value shl 8) or value

    @Test
    fun uniformImageStaysUniform() {
        val pixels = IntArray(12 * 8) { gray(120) }
        blur.boxBlur(pixels, 12, 8, 2, horizontal = true)
        blur.boxBlur(pixels, 12, 8, 2, horizontal = false)
        assertTrue(pixels.all { it == gray(120) })
    }

    @Test
    fun brightPixelIsSpreadToItsNeighbours() {
        val width = 9
        val pixels = IntArray(width * 9) { gray(0) }
        pixels[4 * width + 4] = gray(250)
        blur.boxBlur(pixels, width, 9, 2, horizontal = true)
        blur.boxBlur(pixels, width, 9, 2, horizontal = false)
        // 5x5 window: 250 / 25 = 10 everywhere within 2 pixels of the center, 0 beyond
        assertEquals(gray(10), pixels[4 * width + 4])
        assertEquals(gray(10), pixels[2 * width + 6])
        assertEquals(gray(0), pixels[0])
    }

    @Test
    fun tinyImagesDoNotCrash() {
        val pixels = IntArray(1) { gray(42) }
        blur.boxBlur(pixels, 1, 1, 4, horizontal = true)
        blur.boxBlur(pixels, 1, 1, 4, horizontal = false)
        assertEquals(gray(42), pixels[0])
    }
}
