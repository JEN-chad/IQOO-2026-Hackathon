package ai.safescreen.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.view.WindowManager
import androidx.core.content.IntentCompat
import ai.safescreen.SafeScreenEngine
import ai.safescreen.bench.EnergyMonitor
import ai.safescreen.policy.Severity
import kotlinx.coroutines.runBlocking

/**
 * Foreground service: continuously mirrors the screen via MediaProjection, runs every (throttled)
 * frame through the detectors, and asks the OverlayManager to blur/warn when content is flagged.
 * 100% on-device — frames are processed in memory and never stored or sent anywhere.
 */
class ScreenCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var vdisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private lateinit var overlay: OverlayManager
    private lateinit var engine: SafeScreenEngine
    private lateinit var energy: EnergyMonitor
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null
    private var lastProcMs = 0L
    private var busy = false
    private var lastBlurMs = 0L

    /** Periodic refresh of the live energy/battery readout in the notification + overlay panel. */
    private val energyTick = object : Runnable {
        override fun run() {
            energy.samplePower()
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
            overlay.updateEnergy(energyLine())
            bgHandler?.postDelayed(this, ENERGY_TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        overlay = OverlayManager(this)
        engine = SafeScreenEngine.get(this)
        energy = EnergyMonitor(this).apply { backend = engine.backend; start() }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "SafeScreen protection", NotificationManager.IMPORTANCE_LOW),
        )
        bgThread = HandlerThread("ss-capture").also { it.start() }
        bgHandler = Handler(bgThread!!.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopAll(); return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, buildNotification())
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_DATA, Intent::class.java) }
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        projection = mpm.getMediaProjection(code, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopAll() }
        }, bgHandler)
        startCapture()
        return START_STICKY
    }

    private fun startCapture() {
        val wm = getSystemService(WindowManager::class.java)
        val bounds = wm.maximumWindowMetrics.bounds
        val dpi = resources.configuration.densityDpi
        val scale = 1080f / maxOf(bounds.width(), bounds.height()).coerceAtLeast(1)
        val capW = (bounds.width() * scale).toInt().coerceAtLeast(1)
        val capH = (bounds.height() * scale).toInt().coerceAtLeast(1)

        reader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2).apply {
            setOnImageAvailableListener({ r ->
                val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                val now = SystemClock.elapsedRealtime()
                // Skip while the overlay is covering the screen — otherwise we'd re-classify our own
                // warning overlay (it gets captured too) and flicker.
                if (busy || overlay.isShowing() || now - lastProcMs < THROTTLE_MS) {
                    image.close(); return@setOnImageAvailableListener
                }
                lastProcMs = now; busy = true
                try {
                    val bmp = imageToBitmap(image)
                    image.close()
                    process(bmp)
                } catch (t: Throwable) {
                    runCatching { image.close() }
                } finally {
                    busy = false
                }
            }, bgHandler)
        }
        vdisplay = projection!!.createVirtualDisplay(
            "SafeScreenCap", capW, capH, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, bgHandler,
        )
        bgHandler?.postDelayed(energyTick, ENERGY_TICK_MS)
    }

    private fun process(bmp: Bitmap) {
        if (overlay.isCoolingDown()) return
        val hash = frameHash(bmp)
        if (overlay.isRevealedFor(hash)) return
        val analyzed = runBlocking { engine.analyzeScreen(bmp) }
        energy.recordInference(analyzed.hud.tier1Ms)
        val d = analyzed.decision
        val now = SystemClock.elapsedRealtime()
        if (d.severity.ordinal >= Severity.MEDIUM.ordinal) {
            lastBlurMs = now
            val score = "NSFW ${(d.nsfw * 100).toInt()}%"
            overlay.showBlur(bmp, hash, d.reason, score)
        } else if (now - lastBlurMs >= HOLD_MS) {
            overlay.hide()
        }
    }

    /** 64-bit average-hash (aHash) of the frame's CENTER square, to detect on-screen content change.
     *  Hashing the center (where the viewed image lives) — not the whole screen — keeps unchanging
     *  chrome (status/nav bars, gallery UI) from dominating, so a swipe to a new image actually
     *  changes the hash and expires a stale reveal. */
    private fun frameHash(b: Bitmap): Long {
        val side = minOf(b.width, b.height)
        val sq = if (b.width == side && b.height == side) b
        else Bitmap.createBitmap(b, (b.width - side) / 2, (b.height - side) / 2, side, side)
        val s = 8
        val small = Bitmap.createScaledBitmap(sq, s, s, true)
        if (sq !== b) sq.recycle()
        val px = IntArray(s * s)
        small.getPixels(px, 0, s, 0, 0, s, s)
        if (small !== sq) small.recycle()
        val gray = IntArray(s * s)
        var sum = 0L
        for (i in px.indices) {
            val p = px[i]
            val g = ((p shr 16 and 0xFF) + (p shr 8 and 0xFF) + (p and 0xFF)) / 3
            gray[i] = g; sum += g
        }
        val mean = sum / (s * s)
        var h = 0L
        for (i in gray.indices) if (gray[i] > mean) h = h or (1L shl i)
        return h
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val wide = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888,
        )
        wide.copyPixelsFromBuffer(plane.buffer)
        return if (rowPadding == 0) wide else Bitmap.createBitmap(wide, 0, 0, image.width, image.height)
    }

    private fun stopAll() {
        bgHandler?.removeCallbacks(energyTick)
        runCatching { vdisplay?.release() }
        runCatching { reader?.close() }
        runCatching { projection?.stop() }
        overlay.destroy()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        runCatching { bgThread?.quitSafely() }
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val ms = energy.latencyMs
        val mw = energy.batteryMw
        val lvl = energy.batteryLevel
        val be = if (energy.backend == "QNN/HTP") "NPU (QNN/HTP)" else energy.backend

        val collapsed = if (mw > 0) "$lvl% · ${"%.0f".format(mw)} mW · ${ms}ms/frame on NPU"
        else "On-device · ${ms}ms/frame on NPU"

        val big = StringBuilder()
            .append("🔒 100% on-device · 0 bytes leave your phone\n")
            .append("NPU: ${ms} ms/inference · ${"%.0f".format(energy.computeFps())} fps capable · $be\n")
        if (energy.pluggedIn) {
            big.append("Energy: ⚡ charging — unplug for a valid number\n")
        } else {
            val mj = energy.mjPerInference()
            if (mj > 0) {
                big.append("Energy: ~${"%.1f".format(mj)} mJ/frame · ${"%.0f".format(energy.inferencesPerJoule())} inf/J\n")
            }
        }
        val hrs = energy.projectedHours()
        big.append(
            if (mw > 0) "Battery: $lvl% · ${"%.0f".format(mw)} mW total" +
                (if (hrs > 0) " · ~${"%.0f".format(hrs)} h/charge" else "")
            else "Battery: $lvl%",
        )

        return Notification.Builder(this, CHANNEL)
            .setContentTitle("SafeScreen · NPU protecting your screen")
            .setContentText(collapsed)
            .setStyle(Notification.BigTextStyle().bigText(big.toString()))
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setOngoing(true)
            .build()
    }

    /** Compact one-liner for the overlay control panel. */
    private fun energyLine(): String {
        val parts = ArrayList<String>()
        parts.add("NPU ${energy.latencyMs}ms")
        if (energy.batteryMw > 0) parts.add("${"%.0f".format(energy.batteryMw)} mW")
        if (!energy.pluggedIn) {
            val mj = energy.mjPerInference()
            if (mj > 0) parts.add("${"%.1f".format(mj)} mJ/inf")
        }
        parts.add("batt ${energy.batteryLevel}%")
        return parts.joinToString(" · ")
    }

    companion object {
        const val ACTION_STOP = "ai.safescreen.STOP"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"
        private const val NOTIF_ID = 7
        private const val CHANNEL = "safescreen"
        // Blur latency is dominated by this throttle, not compute: a full frame is ~25-35 ms on the
        // 8 Elite CPU (≈1 ms on the NPU). 80 ms (~12 fps) halves the "I can still see it" lag when an
        // explicit image/video appears or you swipe to the next one, while staying energy-reasonable.
        // (On the NPU this can drop further almost for free.)
        private const val THROTTLE_MS = 80L
        private const val HOLD_MS = 2000L
        private const val ENERGY_TICK_MS = 1500L

        fun start(context: Context, resultCode: Int, data: Intent) {
            val i = Intent(context, ScreenCaptureService::class.java)
                .putExtra(EXTRA_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ScreenCaptureService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
