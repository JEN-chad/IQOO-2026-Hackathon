package ai.safescreen.pipeline

/**
 * Per-model export/runtime config. inputSize + mean/std must match the Python export exactly.
 */
class ModelConfig(
    val assetPath: String,
    val inputSize: Int,
    val mean: FloatArray,
    val std: FloatArray,
    /** Output indices that count as "positive" (their softmax probs are summed). */
    val positiveIndices: IntArray,
    /** If true the output is already a probability; otherwise softmax/sigmoid is applied. */
    val outputIsProbability: Boolean = false,
) {
    companion object {
        private val IMAGENET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val IMAGENET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)

        // taufiqdp/mobilenetv4_conv_small NSFW (5 classes: drawings,hentai,neutral,porn,sexy).
        // NSFW = hentai+porn+sexy = indices 1,3,4. Conv-net @224 -> fast + HTP-friendly.
        val NSFW = ModelConfig(
            "models/nsfw.pte", 224, IMAGENET_MEAN, IMAGENET_STD,
            positiveIndices = intArrayOf(1, 3, 4),
        )

        // Marqo/nsfw-image-detection-384 (ViT-tiny @384, 2 classes). class 0 = NSFW, class 1 = SFW
        // (verified: benign imgs score class0≈0.06). Higher-accuracy candidate (98.56%); ViT so HTP/NPU
        // delegation is unproven (CPU/XNNPACK works). Used in preference to NSFW if the asset is bundled.
        val NSFW_MARQO = ModelConfig(
            "models/nsfw_marqo.pte", 384, IMAGENET_MEAN, IMAGENET_STD,
            positiveIndices = intArrayOf(0),
        )

        // Marqo compiled to QNN/Hexagon-HTP (int8, SM8750). Same I/O as NSFW_MARQO (384, class 0 = NSFW).
        // Preferred when the QNN backend loads on-device (runs on the NPU, ~2.9 ms vs ~30-60 ms on CPU).
        val NSFW_MARQO_QNN = ModelConfig(
            "models/marqo_qnn.pte", 384, IMAGENET_MEAN, IMAGENET_STD,
            positiveIndices = intArrayOf(0),
        )
    }
}
