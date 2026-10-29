package nl.giejay.android.tv.immich.shared.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import java.security.MessageDigest

/**
 * Frosted-glass background: blurs and dims a small bitmap. Meant to be fed a tiny decode (a few
 * hundred pixels wide) that the view then stretches, which keeps it cheap on low-end TV devices
 * (RenderEffect blur needs Android 12).
 */
class SoftBlurTransformation(private val radius: Int = 4, private val dim: Float = 0.45f) : BitmapTransformation() {

    override fun transform(pool: BitmapPool, toTransform: Bitmap, outWidth: Int, outHeight: Int): Bitmap {
        val width = toTransform.width
        val height = toTransform.height
        val result = pool.get(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        toTransform.getPixels(pixels, 0, width, 0, 0, width, height)
        // Three box blur passes approximate a gaussian blur
        repeat(3) {
            boxBlur(pixels, width, height, radius, horizontal = true)
            boxBlur(pixels, width, height, radius, horizontal = false)
        }
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        Canvas(result).drawColor(Color.argb((dim * 255).toInt(), 0, 0, 0))
        return result
    }

    // Sliding-window average along rows or columns, clamping at the edges
    internal fun boxBlur(pixels: IntArray, width: Int, height: Int, radius: Int, horizontal: Boolean) {
        val length = if (horizontal) width else height
        val lines = if (horizontal) height else width
        val line = IntArray(length)
        val window = 2 * radius + 1
        for (l in 0 until lines) {
            for (i in 0 until length) line[i] = pixels[index(l, i, width, horizontal)]
            var r = 0; var g = 0; var b = 0
            for (k in -radius..radius) {
                val c = line[k.coerceIn(0, length - 1)]
                r += c shr 16 and 0xff; g += c shr 8 and 0xff; b += c and 0xff
            }
            for (i in 0 until length) {
                pixels[index(l, i, width, horizontal)] =
                    (0xff shl 24) or ((r / window) shl 16) or ((g / window) shl 8) or (b / window)
                val outgoing = line[(i - radius).coerceIn(0, length - 1)]
                val incoming = line[(i + radius + 1).coerceIn(0, length - 1)]
                r += (incoming shr 16 and 0xff) - (outgoing shr 16 and 0xff)
                g += (incoming shr 8 and 0xff) - (outgoing shr 8 and 0xff)
                b += (incoming and 0xff) - (outgoing and 0xff)
            }
        }
    }

    private fun index(line: Int, i: Int, width: Int, horizontal: Boolean) =
        if (horizontal) line * width + i else i * width + line

    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update("SoftBlurTransformation-$radius-$dim".toByteArray(CHARSET))
    }

    override fun equals(other: Any?) = other is SoftBlurTransformation && other.radius == radius && other.dim == dim

    override fun hashCode() = 31 * radius + dim.hashCode()
}
