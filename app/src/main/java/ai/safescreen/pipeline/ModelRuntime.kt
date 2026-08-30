package ai.safescreen.pipeline

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/** Backend-selectable inference primitive: float input tensor -> float output logits. */
interface ModelRuntime {
    val backend: String
    fun run(input: FloatArray, shape: LongArray): FloatArray
    fun close()
}

/**
 * Real ExecuTorch runtime wrapping org.pytorch.executorch.Module.
 * Construction via [tryLoad] is failure-safe: if the AAR native libs or the .pte are
 * missing/incompatible, it returns null and callers fall back to a heuristic detector.
 */
class ExecuTorchRuntime private constructor(
    private val module: org.pytorch.executorch.Module,
    override val backend: String,
) : ModelRuntime {

    // The native ExecuTorch Module is NOT thread-safe (concurrent forward() races in the lazy
    // method-loader and corrupts the heap). Serialize all calls through this runtime.
    @Synchronized
    override fun run(input: FloatArray, shape: LongArray): FloatArray {
        val tensor = org.pytorch.executorch.Tensor.fromBlob(input, shape)
        val outputs = module.forward(org.pytorch.executorch.EValue.from(tensor))
        return outputs[0].toTensor().dataAsFloatArray
    }

    override fun close() {
        // 0.6.0 Module has no explicit close; rely on GC.
    }

    companion object {
        private const val TAG = "ExecuTorchRuntime"

        @Volatile private var qnnReady: Boolean? = null

        /**
         * Lazily load the QNN/Hexagon backend so a QNN-delegated .pte runs on the NPU. Requires the QNN
         * .so libs in jniLibs and ADSP_LIBRARY_PATH pointing at the app's native-lib dir (so the HTP skel
         * is found). Returns false if the prebuilt runtime can't register QNN (caller then uses CPU).
         */
        @Synchronized
        fun ensureQnn(context: Context): Boolean {
            qnnReady?.let { return it }
            val ok = try {
                Class.forName("org.pytorch.executorch.Module") // force core libexecutorch.so to load first
                val nativeDir = context.applicationInfo.nativeLibraryDir
                // Include the system DSP search paths (where the unsigned-PD FastRPC shell + skel deps live)
                // in addition to our bundled skel; overwriting with only nativeDir starves the unsigned PD.
                val adsp = "$nativeDir;/vendor/lib/rfsa/adsp;/vendor/dsp/cdsp;/vendor/dsp;/dsp;/system/lib/rfsa/adsp;/system_ext/lib/rfsa/adsp"
                android.system.Os.setenv("ADSP_LIBRARY_PATH", adsp, true)
                android.system.Os.setenv("LD_LIBRARY_PATH", nativeDir, true)
                System.loadLibrary("qnn_executorch_backend") // its ctor registers "QnnBackend"
                Log.i(TAG, "QNN backend registered (ADSP_LIBRARY_PATH=$nativeDir)")
                true
            } catch (t: Throwable) {
                Log.w(TAG, "QNN backend unavailable -> CPU: ${t.message}")
                false
            }
            qnnReady = ok
            return ok
        }

        /**
         * Loads a model, preferring a dev override pushed via adb (no APK rebuild needed):
         *   adb push model.pte /sdcard/Android/data/ai.safescreen/files/models/<name>.pte
         * then falling back to the bundled asset. Returns null on any failure.
         * If [qnn] is set, registers the QNN backend first; returns null if QNN can't load.
         */
        fun tryLoad(
            context: Context,
            assetPath: String,
            backend: String = "CPU/XNNPACK",
            qnn: Boolean = false,
        ): ExecuTorchRuntime? {
            if (qnn && !ensureQnn(context)) return null
            return try {
                val name = assetPath.substringAfterLast('/')
                val override = File(context.getExternalFilesDir("models"), name)
                val path = if (override.exists()) {
                    Log.i(TAG, "Using dev override ${override.absolutePath}")
                    override.absolutePath
                } else {
                    copyAsset(context, assetPath)?.absolutePath
                }
                if (path == null) null
                else {
                    val module = org.pytorch.executorch.Module.load(path)
                    Log.i(TAG, "Loaded $name on $backend")
                    ExecuTorchRuntime(module, backend)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "ExecuTorch load failed for $assetPath: ${t.message}")
                null
            }
        }

        private fun copyAsset(context: Context, assetPath: String): File? = try {
            val outFile = File(context.filesDir, assetPath.substringAfterLast('/'))
            context.assets.open(assetPath).use { input ->
                FileOutputStream(outFile).use { output -> input.copyTo(output) }
            }
            outFile
        } catch (t: Throwable) {
            Log.w(TAG, "Asset $assetPath unavailable: ${t.message}")
            null
        }
    }
}
