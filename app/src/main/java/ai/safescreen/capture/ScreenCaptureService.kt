package ai.safescreen.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.view.WindowManager
import androidx.core.content.IntentCompat
import ai.safescreen.SafeScreenEngine
import ai.safescreen.bench.EnergyMonitor
import ai.safescreen.policy.Severity
import ai.safescreen.policy.TemporalSmoother
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    private val smoother = TemporalSmoother(decayAlpha = 0.5f)
    private val frameSeq = java.util.concurrent.atomic.AtomicLong(1000L)
    private val latestCapturedFrameId = java.util.concurrent.atomic.AtomicLong(0L)
    private val latestCapturedHash = java.util.concurrent.atomic.AtomicLong(0L)

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            android.util.Log.i("ScreenCaptureService", "MediaProjection stopped by system/user")
            stopAll()
        }
    }

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_DATA, Intent::class.java) }
        if (code != 0 && data != null && projection == null) {
            val mp = getSystemService(MediaProjectionManager::class.java)
            val proj = mp.getMediaProjection(code, data)
            proj.registerCallback(projectionCallback, bgHandler)
            projection = proj
            startCapture()
        }
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
                val frameId = frameSeq.incrementAndGet()
                val minInterval = if (overlay.isShowing()) 250L else THROTTLE_MS
                if (busy || now - lastProcMs < minInterval) {
                    image.close()
                    return@setOnImageAvailableListener
                }
                lastProcMs = now
                busy = true
                latestCapturedFrameId.set(frameId)
                try {
                    android.util.Log.i("ScreenCaptureService", "[FRAME $frameId] CAPTURED t=$now")
                    val bmp = imageToBitmap(image)
                    val hash = frameHash(bmp)
                    latestCapturedHash.set(hash)
                    image.close()
                    process(bmp, frameId, now, hash)
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
        _isRunning.value = true
    }

    private fun process(bmp: Bitmap, frameId: Long, captureTimestamp: Long, hash: Long) {
        if (overlay.isCoolingDown()) return
        if (overlay.isRevealedFor(hash)) return
        val analyzed = runBlocking { engine.analyzeScreen(bmp, frameId) }
        energy.recordInference(analyzed.hud.tier1Ms)
        val now = SystemClock.elapsedRealtime()
        val frameAge = now - captureTimestamp
        android.util.Log.i("ScreenCaptureService", "[FRAME $frameId] RESULT_RECEIVED t=$now age=${frameAge}ms")

        // Stale Result Protection: If a newer frame arrived during inference AND current screen hash changed,
        // discard this obsolete result so we don't apply an outdated screen decision to the new screen!
        if (latestCapturedFrameId.get() > frameId && latestCapturedHash.get() != hash) {
            android.util.Log.w(
                "ScreenCaptureService",
                "[FRAME $frameId] STALE_RESULT_DISCARDED age=${frameAge}ms latestId=${latestCapturedFrameId.get()}",
            )
            return
        }

        val blurThreshold = engine.thresholds.nsfwBlur
        val smoothedScore = smoother.push(analyzed.decision.nsfw, blurThreshold)
        val d = analyzed.decision

        if (smoothedScore >= blurThreshold || d.severity.ordinal >= Severity.MEDIUM.ordinal) {
            lastBlurMs = now
            val scoreText = "Risk: ${(smoothedScore * 100).toInt()}% • Level: ${engine.currentLevel.title}"
            android.util.Log.i("ScreenCaptureService", "[FRAME $frameId] OVERLAY_UPDATE t=$now action=BLUR score=$scoreText")
            overlay.showBlur(bmp, hash, d.reason, scoreText, engine.currentLevel.title)
        } else if (now - lastBlurMs >= HOLD_MS) {
            android.util.Log.i("ScreenCaptureService", "[FRAME $frameId] OVERLAY_UPDATE t=$now action=HIDE")
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
        _isRunning.value = false
        smoother.reset()
        bgHandler?.removeCallbacks(energyTick)
        bgThread?.quitSafely()
        bgThread = null; bgHandler = null
        runCatching { vdisplay?.release() }
        runCatching { reader?.close() }
        runCatching {
            projection?.unregisterCallback(projectionCallback)
            projection?.stop()
        }
        vdisplay = null; reader = null; projection = null
        overlay.destroy()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopAll()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val mw = energy.batteryMw
        val lvl = energy.batteryLevel
        val ms = energy.latencyMs
        val be = engine.backend
        val level = engine.currentLevel.title
        val collapsed = "SafeScreen [$level] Active · ${ms}ms latency · $be"

        val big = StringBuilder()
            .append("🔒 LOCAL AI • ZERO BYTES TRANSMITTED\n")
            .append("Shield Level: $level\n")
            .append("Backend: $be • Latency: ${ms}ms\n")
        if (energy.pluggedIn) {
            big.append("Battery: $lvl% (Charging)\n")
        } else {
            big.append("Battery: $lvl% (${"%.0f".format(mw)} mW)\n")
        }

        return Notification.Builder(this, CHANNEL)
            .setContentTitle("SafeScreen AI • [$level] Active")
            .setContentText(collapsed)
            .setStyle(Notification.BigTextStyle().bigText(big.toString()))
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setOngoing(true)
            .build()
    }

    /** Compact one-liner for the overlay control panel. */
    private fun energyLine(): String {
        val parts = ArrayList<String>()
        parts.add("${engine.backend} ${energy.latencyMs}ms")
        if (energy.batteryMw > 0) parts.add("${"%.0f".format(energy.batteryMw)} mW")
        parts.add("batt ${energy.batteryLevel}%")
        return parts.joinToString(" · ")
    }

    companion object {
        const val ACTION_STOP = "ai.safescreen.STOP"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"
        private const val NOTIF_ID = 7
        private const val CHANNEL = "safescreen"
        private const val THROTTLE_MS = 80L
        private const val HOLD_MS = 2000L
        private const val ENERGY_TICK_MS = 1500L

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning

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
