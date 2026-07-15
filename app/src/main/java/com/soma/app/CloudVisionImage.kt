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
    fun prepare(
        jpeg: ByteArray,
        rotationDegrees: Int,
        maxPixels: Int = DEFAULT_MAX_PIXELS,
    ): ByteArray? {
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
        // Vision models charge input tokens per pixel patch, so the area cap —
        // not the byte size — is what controls provider cost and rate budgets.
        val dimensionRatio = MAX_DIMENSION.toFloat() / maxOf(decoded.width, decoded.height)
        val areaRatio = kotlin.math.sqrt(
            maxPixels.toDouble() / (decoded.width.toDouble() * decoded.height.toDouble()),
        ).toFloat()
        val ratio = minOf(1f, dimensionRatio, areaRatio)
        val scaled = if (ratio < 1f) {
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

    /**
     * Halves the pixel area of an already prepared request image for a single
     * polite retry after a rate limit — fewer patches, roughly half the input
     * tokens, still comfortably readable for printed receipt text.
     */
    fun shrinkForRetry(prepared: ByteArray): ByteArray? {
        val decoded = BitmapFactory.decodeByteArray(prepared, 0, prepared.size) ?: return null
        val ratio = kotlin.math.sqrt(0.5).toFloat()
        val scaled = Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * ratio).toInt().coerceAtLeast(1),
            (decoded.height * ratio).toInt().coerceAtLeast(1),
            true,
        ).also { if (it !== decoded) decoded.recycle() }
        return try {
            compress(scaled, JPEG_QUALITY).takeIf { it.size <= MAX_OUTPUT_BYTES }
        } finally {
            scaled.recycle()
        }
    }

    private fun compress(bitmap: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output))
            output.toByteArray()
        }

    private const val MAX_DIMENSION = 2_048

    /** The general budget matches the historical 2048-square worst case. */
    const val DEFAULT_MAX_PIXELS = MAX_DIMENSION * MAX_DIMENSION

    /**
     * Receipts are narrow strips of high-contrast print; a tighter area budget
     * keeps them readable while roughly a third of the general token cost,
     * which is the difference between one and several scans per minute on a
     * provider free tier.
     */
    const val RECEIPT_MAX_PIXELS = 1_400_000

    // Base64 inflates by 4/3 and the strictest candidate vision model caps the
    // encoded image at 4 MB, so the prepared JPEG must stay under ~2.9 MB raw.
    private const val MAX_OUTPUT_BYTES = 2_900_000
    private const val JPEG_QUALITY = 86
    private const val FALLBACK_JPEG_QUALITY = 72
}
