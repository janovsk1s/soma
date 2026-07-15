package com.soma.app

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CloudVisionImageTest {
    @Test
    fun `the receipt budget caps pixel area and keeps the aspect ratio`() {
        val prepared = CloudVisionImage.prepare(
            jpegOf(3_000, 1_200),
            rotationDegrees = 0,
            maxPixels = CloudVisionImage.RECEIPT_MAX_PIXELS,
        )

        assertNotNull(prepared)
        val size = decodedSize(prepared!!)
        assertTrue(size.first * size.second <= CloudVisionImage.RECEIPT_MAX_PIXELS)
        assertEquals(2.5, size.first.toDouble() / size.second, 0.05)
    }

    @Test
    fun `the default budget leaves an ordinary photo untouched`() {
        val prepared = CloudVisionImage.prepare(jpegOf(2_000, 1_500), rotationDegrees = 0)

        assertNotNull(prepared)
        assertEquals(2_000 to 1_500, decodedSize(prepared!!))
    }

    @Test
    fun `shrink for retry halves the pixel area`() {
        val prepared = CloudVisionImage.prepare(jpegOf(1_600, 1_000), rotationDegrees = 0)!!

        val shrunk = CloudVisionImage.shrinkForRetry(prepared)

        assertNotNull(shrunk)
        val (width, height) = decodedSize(shrunk!!)
        val ratio = (width.toDouble() * height) / (1_600.0 * 1_000.0)
        assertEquals(0.5, ratio, 0.05)
    }

    private fun jpegOf(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
            bitmap.recycle()
            output.toByteArray()
        }
    }

    private fun decodedSize(jpeg: ByteArray): Pair<Int, Int> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        return bounds.outWidth to bounds.outHeight
    }
}
