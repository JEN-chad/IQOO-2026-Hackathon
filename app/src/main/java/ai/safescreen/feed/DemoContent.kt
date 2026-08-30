package ai.safescreen.feed

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader

data class FeedItem(val id: String, val caption: String, val bitmap: Bitmap)

/**
 * Demo feed source. Prefers REAL images bundled in assets/demo/ (so the real model scores them
 * sensibly); falls back to procedurally-generated tiles when no assets are present (zero-dependency
 * demo that still exercises the full pipeline with the heuristic detector).
 *
 * Drop .jpg/.png files into app/src/main/assets/demo/ — the filename becomes the caption.
 */
object DemoContent {
    fun items(context: Context): List<FeedItem> = loadFromAssets(context).ifEmpty { procedural() }

    private fun loadFromAssets(context: Context): List<FeedItem> = try {
        val names = context.assets.list("demo")
            ?.filter { it.endsWith(".jpg", true) || it.endsWith(".jpeg", true) || it.endsWith(".png", true) }
            ?.sorted()
            ?: emptyList()
        names.mapNotNull { name ->
            try {
                val bmp = context.assets.open("demo/$name").use { BitmapFactory.decodeStream(it) }
                bmp?.let { FeedItem(name, caption(name), it) }
            } catch (t: Throwable) {
                null
            }
        }
    } catch (t: Throwable) {
        emptyList()
    }

    private fun caption(name: String): String =
        name.substringBeforeLast('.').replace('_', ' ').replaceFirstChar { it.uppercase() }

    // ---- Procedural fallback (no bundled assets) ----

    private const val W = 720
    private const val H = 560

    private fun procedural(): List<FeedItem> = listOf(
        landscape("img-1", "Mountain lake at dawn", Color.rgb(70, 130, 200), Color.rgb(200, 230, 255)),
        skin("img-2", "Beach portrait", 0.75f),
        landscape("img-3", "Forest trail", Color.rgb(34, 90, 34), Color.rgb(150, 200, 120)),
        skin("img-4", "Swimwear photo (proxy)", 0.95f),
        faceLike("img-5", "Profile headshot"),
        landscape("img-6", "City skyline at dusk", Color.rgb(40, 40, 70), Color.rgb(230, 170, 110)),
        skin("img-7", "Close-up portrait", 0.55f),
        faceLike("img-8", "ID-style photo"),
    )

    private fun base(): Bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)

    private fun landscape(id: String, caption: String, top: Int, bottom: Int): FeedItem {
        val bmp = base()
        val c = Canvas(bmp)
        val p = Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, H.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), p)
        return FeedItem(id, caption, bmp)
    }

    private fun skin(id: String, caption: String, coverage: Float): FeedItem {
        val bmp = base()
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(150, 200, 230))
        val p = Paint().apply { color = Color.rgb(225, 170, 140) }
        c.drawRect(0f, H - H * coverage, W.toFloat(), H.toFloat(), p)
        return FeedItem(id, caption, bmp)
    }

    private fun faceLike(id: String, caption: String): FeedItem {
        val bmp = base()
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(60, 70, 90))
        val skin = Paint().apply { color = Color.rgb(228, 175, 145); isAntiAlias = true }
        c.drawOval(W * 0.30f, H * 0.18f, W * 0.70f, H * 0.88f, skin)
        val dark = Paint().apply { color = Color.rgb(30, 30, 30); isAntiAlias = true }
        c.drawCircle(W * 0.42f, H * 0.45f, 14f, dark)
        c.drawCircle(W * 0.58f, H * 0.45f, 14f, dark)
        return FeedItem(id, caption, bmp)
    }
}
