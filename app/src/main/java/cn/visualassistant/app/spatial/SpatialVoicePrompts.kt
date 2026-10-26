package cn.visualassistant.app.spatial

/** Latest state only: never queue outdated instructions, never speak on every render frame. */
class SpatialVoicePrompts {
    private var phase: ScanPhase? = null
    private var enteredAt = 0L
    private var lastSpoken: ScanPhase? = null
    private var lastSpokenAt: Long? = null

    fun next(snapshot: SpatialSnapshot, now: Long, busy: Boolean): String? {
        if (phase != snapshot.phase) { phase = snapshot.phase; enteredAt = now }
        if (busy) return null
        val changed = lastSpoken != snapshot.phase
        if (changed && lastSpoken != null && now-enteredAt < 1000) return null
        val delay = if (snapshot.phase == ScanPhase.READY) 20000 else 15000
        if (!changed && lastSpokenAt?.let { now-it < delay } == true) return null
        val text = when(snapshot.phase) {
            ScanPhase.LOCATING -> "正在建立定位。请先站定，将手机朝向有纹理的物体，缓慢左右平移，不必往前走。"
            ScanPhase.WAITING_DEPTH -> "定位已建立，正在等待深度。请原地把手机缓慢左右平移二十厘米，尽量避开白墙、反光和过暗区域。"
            ScanPhase.ACCUMULATING -> "已收到深度，正在确认空间表面。请继续缓慢平移手机。"
            ScanPhase.READY -> {
                if (!snapshot.usable || now-snapshot.time > 1500) return null
                (if(changed) "空间扫描已可用。" else "空间更新。") + snapshot.describe("周围有什么",now,2)
            }
            ScanPhase.LOST -> "空间定位丢失，请停下。原地图已清除，请重新朝向有纹理的区域扫描。"
            ScanPhase.LEVEL_PHONE -> "请将手机朝向前方，暂时不要朝向天花板或脚下。"
            ScanPhase.ERROR -> "空间数据中断，暂不能提供方向距离，请结束扫描后重试。"
            ScanPhase.IDLE -> return null
        }
        lastSpoken=snapshot.phase; lastSpokenAt=now
        return text
    }

    fun reset() { phase=null; lastSpoken=null; lastSpokenAt=null; enteredAt=0 }
}
