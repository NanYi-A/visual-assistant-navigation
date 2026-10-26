package cn.visualassistant.app.feedback

enum class SpeechPriority { INFORMATION, STATUS, ANSWER, NAVIGATION, EMERGENCY }

data class SpeechTicket(
    val id: String,
    val key: String,
    val text: String,
    val priority: SpeechPriority,
    val createdAt: Long,
    val expiresAt: Long,
)

data class SpeechTransition(
    val start: SpeechTicket? = null,
    val stopCurrent: Boolean = false,
    val discarded: List<SpeechTicket> = emptyList(),
)

/** Pure queue policy. TTS callbacks and Android lifecycle remain in FeedbackController. */
class SpeechArbiter(private val duplicateWindowMs: Long = 8_000) {
    private var current: SpeechTicket? = null
    private val pending = LinkedHashMap<String, SpeechTicket>()
    private val lastCompleted = HashMap<String, Long>()

    fun offer(ticket: SpeechTicket, now: Long): SpeechTransition {
        if (ticket.expiresAt <= now || (ticket.priority!=SpeechPriority.ANSWER &&
                lastCompleted[ticket.text]?.let { now-it < duplicateWindowMs } == true))
            return SpeechTransition(discarded=listOf(ticket))
        val active=current
        if(active==null) { current=ticket; return SpeechTransition(start=ticket) }
        if(active.key==ticket.key && active.text==ticket.text)
            return SpeechTransition(discarded=listOf(ticket))
        if(ticket.priority==SpeechPriority.EMERGENCY && active.priority!=SpeechPriority.EMERGENCY) {
            current=ticket
            return SpeechTransition(start=ticket,stopCurrent=true,discarded=listOf(active))
        }
        val replaced=pending.put(ticket.key,ticket)
        return SpeechTransition(discarded=listOfNotNull(replaced))
    }

    fun complete(id: String, now: Long): SpeechTransition {
        val active=current ?: return SpeechTransition()
        if(active.id!=id) return SpeechTransition()
        lastCompleted[active.text]=now
        current=null
        val expired=pending.values.filter { it.expiresAt<=now }
        expired.forEach { pending.remove(it.key) }
        val next=pending.values.sortedWith(compareByDescending<SpeechTicket> { it.priority.ordinal }
            .thenByDescending { it.createdAt }).firstOrNull()
        if(next!=null) { pending.remove(next.key); current=next }
        return SpeechTransition(start=next,discarded=expired)
    }

    fun cancelNonEmergency(): SpeechTransition {
        val discarded=pending.values.filter { it.priority!=SpeechPriority.EMERGENCY }.toMutableList()
        discarded.forEach { pending.remove(it.key) }
        val active=current
        return if(active!=null && active.priority!=SpeechPriority.EMERGENCY) {
            current=null; discarded+=active
            SpeechTransition(stopCurrent=true,discarded=discarded)
        } else SpeechTransition(discarded=discarded)
    }

    fun clear(): SpeechTransition {
        val discarded=pending.values.toMutableList(); pending.clear()
        current?.let(discarded::add); val stop=current!=null; current=null
        return SpeechTransition(stopCurrent=stop,discarded=discarded)
    }

    /**
     * True while something is playing or still waiting to play. Callers must use this
     * instead of tracking their own flag: when a pending ticket is replaced by a newer
     * one with the same key, the old completion callback still fires and would report
     * idle while the replacement is queued.
     */
    fun isBusy(): Boolean = current != null || pending.isNotEmpty()
}
