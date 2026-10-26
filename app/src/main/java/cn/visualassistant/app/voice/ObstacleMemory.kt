package cn.visualassistant.app.voice

import cn.visualassistant.app.core.Detection
import cn.visualassistant.app.core.NormalizedBox

/** Bounded visual history, NOT world coordinates. Identity association is only local and tentative. */
class ObstacleMemory {
    private data class Track(var item: Detection, var last: Long, var hits: Int = 1,
        var confirmed: Boolean = false)
    private val tracks = mutableListOf<Track>()
    private var lastFrame: Long? = null
    private var expiredUnresolved = false
    private var lastReminder: Long? = null
    private var reminded = false

    fun update(items: List<Detection>, now: Long) {
        if (lastFrame?.let { now < it } == true) clear()
        prune(now)
        val gap = lastFrame?.let { now - it } ?: 0
        lastFrame = now
        val available = tracks.toMutableList()
        items.filter { it.confidence >= .45f }.sortedByDescending { it.confidence }.take(24).forEach { item ->
            val match = available.filter { now - it.last <= 1000 && it.item.classId == item.classId }
                .maxByOrNull { overlap(it.item.box, item.box) }
                ?.takeIf { overlap(it.item.box, item.box) >= .3f }
            if (match != null) {
                available.remove(match)
                match.hits = if (gap <= 1000 && now - match.last == gap) match.hits + 1 else 1
                match.confirmed = match.confirmed || match.hits >= 2
                match.item = item
                match.last = now
            } else tracks.add(Track(item, now))
        }
        while (tracks.size > 24) {
            val removed = tracks.minBy { it.last }
            if (removed.confirmed && near(removed.item)) expiredUnresolved = true
            tracks.remove(removed)
        }
        if (!unresolved(now)) reminded = false
    }

    private fun prune(now: Long) {
        tracks.removeAll {
            val expired = now - it.last > if (it.confirmed) 15000 else 1000
            if (expired && it.confirmed && near(it.item)) expiredUnresolved = true
            expired
        }
    }

    fun unresolved(now: Long): Boolean {
        prune(now)
        return expiredUnresolved || tracks.any { it.confirmed && near(it.item) && now > it.last }
    }

    fun caution(now: Long): String? {
        prune(now)
        val lost = tracks.filter { it.confirmed && near(it.item) && now - it.last >= 600 }
        if (lost.isNotEmpty()) {
            val names = lost.map { "${side(it.item)}的${it.item.label}" }.distinct().take(2).joinToString("、")
            return "刚才在画面${names}暂时看不到，尚未确认避开，请停下重新观察。"
        }
        return when {
            expiredUnresolved -> "之前的障碍记录已过期，位置不确定，请重新观察后再移动。"
            unresolved(now) -> "正在重新确认刚才的障碍，请先停下观察。"
            else -> null
        }
    }

    fun reminder(now: Long): String? {
        if (!expiredUnresolved && tracks.none { it.confirmed && near(it.item) && now - it.last >= 600 }) return null
        val message = caution(now) ?: return null
        if (reminded || lastReminder?.let { now - it < 8000 } == true) return null
        reminded = true
        lastReminder = now
        return message
    }

    fun answer(question: String, now: Long): String? {
        if (listOf("多远", "几米", "多少米", "距离").any { it in question }) {
            return "目前只能记住最后看到的画面位置，还不能可靠测距或估计往前走多远会碰到障碍。"
        }
        if (listOf("刚才", "之前", "记得", "记住", "哪里", "在哪").none { it in question }) return null
        prune(now)
        val known = tracks.filter { it.confirmed }
        val named = known.filter { it.item.label in question }
        val generic = listOf("什么", "哪些", "障碍", "记得", "记住").any { it in question }
        val selected = if (named.isNotEmpty()) named else if (generic) known else emptyList()
        if (selected.isEmpty()) return "没有可用的相关障碍记忆，记录可能已过期，请重新观察。"
        val descriptions = selected.sortedByDescending { it.last }
            .distinctBy { it.item.label to side(it.item) }.take(3).map {
                val age = ((now - it.last).coerceAtLeast(0) / 1000)
                "${it.item.label}最后在约${age}秒前出现在画面${side(it.item)}"
            }
        return descriptions.joinToString("；") + "。这是最后看到的位置，不代表现在的位置，也不能确认已经避开。"
    }

    fun clear() {
        tracks.clear()
        lastFrame = null
        expiredUnresolved = false
        lastReminder = null
        reminded = false
    }

    private fun side(d: Detection) = when {
        d.box.centerX < .36f -> "左侧"
        d.box.centerX > .64f -> "右侧"
        else -> "前方"
    }

    private fun near(d: Detection) = d.box.bottom > .55f || d.box.area > .1f

    private fun overlap(a: NormalizedBox, b: NormalizedBox): Float {
        val area = (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0f) *
            (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0f)
        return area / (a.area + b.area - area + 1e-6f)
    }
}
