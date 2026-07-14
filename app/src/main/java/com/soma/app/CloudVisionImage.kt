package com.soma.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import java.io.ByteArrayOutputStream

/**
 * Produces a correctly oriented, bounded cloud-analysis copy while leaving the encrypted original
 * untouched. Smaller requests reduce cellular transfer, latency, provider cost, and peak heat.
 */
internal object CloudVisionImage {
    fun prepare(jpeg: ByteArray, rotationDegrees: Int): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > MAX_DIMENSION * 2) {
            sample *= 2
        }
        val decoded = BitmapFactory.decodeByteArray(
            jpeg,
            0,
            jpeg.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        val scaled = if (maxOf(decoded.width, decoded.height) > MAX_DIMENSION) {
            val ratio = MAX_DIMENSION.toFloat() / maxOf(decoded.width, decoded.height)
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * ratio).toInt().coerceAtLeast(1),
                (decoded.height * ratio).toInt().coerceAtLeast(1),
                true,
            ).also { if (it !== decoded) decoded.recycle() }
        } else {
            decoded
        }
        val normalizedRotation = ((rotationDegrees % 360) + 360) % 360
        val oriented = if (normalizedRotation == 0) {
            scaled
        } else {
            Bitmap.createBitmap(
                scaled,
                0,
                0,
                scaled.width,
                scaled.height,
                Matrix().apply { postRotate(normalizedRotation.toFloat()) },
                true,
            ).also { if (it !== scaled) scaled.recycle() }
        }
        return try {
            val primary = compress(oriented, JPEG_QUALITY)
            if (primary.size <= MAX_OUTPUT_BYTES) {
                primary
            } else {
                primary.fill(0)
                compress(oriented, FALLBACK_JPEG_QUALITY).let { fallback ->
                    if (fallback.size <= MAX_OUTPUT_BYTES) {
                        fallback
                    } else {
                        fallback.fill(0)
                        null
                    }
                }
            }
        } finally {
            oriented.recycle()
        }
    }

    private fun compress(bitmap: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output))
            output.toByteArray()
        }

    private const val MAX_DIMENSION = 2_048
    private const val MAX_OUTPUT_BYTES = 4 * 1024 * 1024
    private const val JPEG_QUALITY = 86
    private const val FALLBACK_JPEG_QUALITY = 72
}
