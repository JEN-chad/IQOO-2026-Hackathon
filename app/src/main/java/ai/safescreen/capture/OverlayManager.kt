package ai.safescreen.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Two-window overlay: a non-touchable full-screen blur (so swipe/back gestures pass through to the
 * app below) + a small touchable control panel with the Reveal button. After reveal, a cooldown
 * suppresses re-blur so the user can navigate away.
 */
class OverlayManager(private val context: Context) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    private var blurView: ImageView? = null
    private var ctrlPanel: LinearLayout? = null
    private var subtitle: TextView? = null
    private var scoreView: TextView? = null
    private var energyView: TextView? = null
    @Volatile private var energyText = ""

    @Volatile private var showing = false
    @Volatile private var shownHash = 0L
    @Volatile private var revealedHash: Long? = null
    @Volatile private var cooldownUntilMs = 0L

    fun isShowing(): Boolean = showing
    fun isCoolingDown(): Boolean = SystemClock.elapsedRealtime() < cooldownUntilMs

    fun isRevealedFor(hash: Long): Boolean {
        val rh = revealedHash ?: return false
        val ham = java.lang.Long.bitCount(hash xor rh)
        return if (ham <= REVEAL_TOL) {
            true
        } else {
            revealedHash = null
            false
        }
    }

    fun showBlur(
        frame: Bitmap,
        hash: Long,
        reason: String,
        score: String,
        level: String = "PRIVATE",
    ) {
        if (isCoolingDown()) return
        showing = true
        shownHash = hash
        main.post {
            ensureViews()
            blurView?.apply {
                setImageBitmap(frame)
                setRenderEffect(RenderEffect.createBlurEffect(80f, 80f, Shader.TileMode.CLAMP))
                visibility = View.VISIBLE
            }
            titleView?.text = "🛡️ Protected by SafeScreen [$level]"
            subtitle?.text = reason
            scoreView?.text = score
            ctrlPanel?.visibility = View.VISIBLE
        }
    }

    fun hide() {
        showing = false
        main.post {
            blurView?.visibility = View.GONE
            ctrlPanel?.visibility = View.GONE
        }
    }

    /** Live NPU + battery energy readout, refreshed by the service ticker while protecting. */
    fun updateEnergy(text: String) {
        energyText = text
        main.post { energyView?.text = text }
    }

    fun destroy() = main.post {
        showing = false
        revealedHash = null
        blurView?.let { runCatching { wm.removeView(it) } }
        ctrlPanel?.let { runCatching { wm.removeView(it) } }
        blurView = null; ctrlPanel = null; subtitle = null; scoreView = null; energyView = null; titleView = null
    }

    private fun reveal() {
        revealedHash = shownHash
        showing = false
        cooldownUntilMs = SystemClock.elapsedRealtime() + COOLDOWN_MS
        blurView?.visibility = View.GONE
        ctrlPanel?.visibility = View.GONE
    }

    private var titleView: TextView? = null

    private fun ensureViews() {
        if (blurView != null) return

        // --- Full-screen blur: FLAG_NOT_TOUCHABLE so swipes/back pass through to the app below ---
        val iv = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        val blurLp = WindowManager.LayoutParams(
            MATCH, MATCH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        runCatching { wm.addView(iv, blurLp) }
        blurView = iv

        // --- Centered control panel: touchable, covers the info + reveal button area ---
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xEE0B0E14.toInt())
            setPadding(72, 56, 72, 56)
        }
        val title = TextView(context).apply {
            text = "🛡️ Protected by SafeScreen"
            setTextColor(Color.WHITE); textSize = 20f; gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        val sub = TextView(context).apply {
            setTextColor(0xFFD7DEE8.toInt()); textSize = 14f; gravity = Gravity.CENTER
            setPadding(0, 16, 0, 12)
        }
        val scores = TextView(context).apply {
            setTextColor(0xFF8FE3A0.toInt()); textSize = 15f; gravity = Gravity.CENTER
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, 10)
        }
        val nrg = TextView(context).apply {
            text = energyText
            setTextColor(0xFFB9C4D0.toInt()); textSize = 12f; gravity = Gravity.CENTER
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, 20)
        }
        val btn = Button(context).apply {
            text = "TAP TO REVEAL"
            setTextColor(Color.WHITE)
            setBackgroundColor(0xFF1E293B.toInt())
            setOnClickListener { reveal() }
        }
        val privacy = TextView(context).apply {
            text = "🔒 LOCAL AI • ZERO BYTES LEAVE PHONE"
            setTextColor(0xFF8FE3A0.toInt()); textSize = 11f; gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 24, 0, 0)
        }
        val hint = TextView(context).apply {
            text = "Swipe or use back gesture to continue navigation"
            setTextColor(0x99FFFFFF.toInt()); textSize = 11f; gravity = Gravity.CENTER
            setPadding(0, 10, 0, 0)
        }
        col.addView(title); col.addView(sub); col.addView(scores); col.addView(nrg)
        col.addView(btn); col.addView(privacy); col.addView(hint)

        val ctrlLp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
        }
        runCatching { wm.addView(col, ctrlLp) }
        ctrlPanel = col; titleView = title; subtitle = sub; scoreView = scores; energyView = nrg
    }

    private companion object {
        const val MATCH = WindowManager.LayoutParams.MATCH_PARENT
        const val REVEAL_TOL = 10
        const val COOLDOWN_MS = 3000L
    }
}

