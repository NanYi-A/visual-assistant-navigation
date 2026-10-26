package cn.visualassistant.app.spatial

/** Only a strictly newer sensor image may refresh depth or become map evidence. */
class DepthStreamGate {
    private var newestSource: Long? = null
    private var lastValidAt: Long? = null
    fun observe(sourceTimestamp: Long, validPoints: Int, now: Long): Boolean {
        if (validPoints < 20) return false
        // ARCore may briefly return cached Raw Depth images, and on the target phone two
        // old timestamps were observed alternating while camera frames kept advancing.
        // Comparing only with the immediately previous image accepted those old images
        // again and attached them to a new pose. Sensor timestamps must be monotonic.
        if (newestSource?.let { sourceTimestamp <= it } == true) return false
        newestSource = sourceTimestamp
        lastValidAt = now
        return true
    }
    fun available(now: Long) = lastValidAt?.let { now-it in 0..1500 } == true
    fun reset() { newestSource=null; lastValidAt=null }
}
