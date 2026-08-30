package ai.safescreen.pipeline

import android.graphics.Bitmap

/**
 * Converts a Bitmap into a normalized NCHW float tensor for ExecuTorch.
 * Reuses internal buffers across calls to avoid per-frame allocation on the hot path.
 * The mean/std MUST match the Python export preprocessing exactly.
 */
class Preprocessor(
    private val size: Int,
    private val mean: FloatArray,
    private val std: FloatArray,
) {
    private val pixels = IntArray(size * size)
    private val out = FloatArray(3 * size * size)

    val shape: LongArray = longArrayOf(1, 3, size.toLong(), size.toLong())

    /**
     * Returns an internal buffer — consume immediately; it is overwritten on the next call.
     * Square inputs are ideal (the screen monitor passes square crops); non-square inputs are squashed.
     */
    fun toTensor(bitmap: Bitmap): FloatArray {
        val scaled =
            if (bitmap.width == size && bitmap.height == size) bitmap
            else Bitmap.createScaledBitmap(bitmap, size, size, true)
        scaled.getPixels(pixels, 0, size, 0, 0, size, size)
        val area = size * size
        var i = 0
        while (i < area) {
            val p = pixels[i]
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr 8) and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            out[i] = (r - mean[0]) / std[0]               // R plane
            out[area + i] = (g - mean[1]) / std[1]         // G plane
            out[2 * area + i] = (b - mean[2]) / std[2]     // B plane
            i++
        }
        if (scaled !== bitmap) scaled.recycle()
        return out
    }
}
