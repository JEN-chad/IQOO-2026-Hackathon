package ai.safescreen.policy

/**
 * Rolling average to stabilize per-frame scores on a continuous stream (Surface B / P1),
 * preventing the overlay from strobing on borderline frames.
 */
class TemporalSmoother(private val window: Int = 5) {
    private val buf = ArrayDeque<Float>()
    private var sum = 0f

    fun push(value: Float): Float {
        buf.addLast(value)
        sum += value
        if (buf.size > window) sum -= buf.removeFirst()
        return sum / buf.size
    }

    fun reset() {
        buf.clear()
        sum = 0f
    }
}
