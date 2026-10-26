package cn.visualassistant.app.feedback

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import cn.visualassistant.app.core.RiskAssessment
import cn.visualassistant.app.core.RiskLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

class FeedbackController(
    context: Context,
    private val onStatus: (String) -> Unit,
) : TextToSpeech.OnInitListener, AutoCloseable {
    private val appContext = context.applicationContext
    private val tts = TextToSpeech(appContext, this)
    private var ready = false
    val isReady: Boolean get() = ready
    private val handler = Handler(Looper.getMainLooper())
    private val arbiter = SpeechArbiter()
    private val callbacks = HashMap<String, () -> Unit>()
    private val timeouts = HashMap<String, Runnable>()
    private var dialogActive = false
    private val mutableBusy = MutableStateFlow(false)
    /**
     * Single source of truth for "is the assistant talking or about to talk".
     * The UI must read this instead of tracking TTS callbacks itself, because a ticket
     * replaced while queued still fires its old completion callback.
     */
    val busy: StateFlow<Boolean> = mutableBusy.asStateFlow()
    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= 31) {
            appContext.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION") appContext.getSystemService(Vibrator::class.java)
        }
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            onStatus("系统语音初始化失败，请安装中文语音引擎")
            return
        }
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { finishSpeech(utteranceId) }
            @Deprecated("Legacy callback")
            override fun onError(utteranceId: String?) { finishSpeech(utteranceId) }
            override fun onError(utteranceId: String?, errorCode: Int) {
                handler.post { onStatus("语音播放失败（$errorCode）") }
                finishSpeech(utteranceId)
            }
        })
        val languageResult = tts.setLanguage(Locale.SIMPLIFIED_CHINESE)
        ready = languageResult != TextToSpeech.LANG_MISSING_DATA && languageResult != TextToSpeech.LANG_NOT_SUPPORTED
        if (!ready) {
            onStatus("缺少中文语音数据，请在系统设置中下载")
        } else {
            onStatus("语音与振动已就绪")
        }
    }

    fun alert(assessment: RiskAssessment) {
        vibrate(assessment.level)
        if (dialogActive && assessment.level != RiskLevel.DANGER) return
        if (assessment.level == RiskLevel.DANGER) dialogActive=false
        val priority=when(assessment.level) {
            RiskLevel.DANGER -> SpeechPriority.EMERGENCY
            RiskLevel.WARNING -> SpeechPriority.NAVIGATION
            RiskLevel.NOTICE -> SpeechPriority.STATUS
            RiskLevel.SAFE -> SpeechPriority.INFORMATION
        }
        speak(assessment.message,priority,"risk-${assessment.level}",if(priority==SpeechPriority.EMERGENCY) 4_000 else 8_000)
    }

    fun beginDialog() {
        apply(arbiter.cancelNonEmergency())
        dialogActive = true
        publishBusy()
    }

    fun speakAnswer(text: String, onDone: () -> Unit = {}) {
        dialogActive = true
        speak(text,SpeechPriority.ANSWER,"answer",30_000) { dialogActive=false; onDone() }
    }

    fun speak(text: String, priority: SpeechPriority, key: String, validForMs: Long=8_000,
        onDone: () -> Unit = {}) {
        if(!ready) { onStatus("语音尚未就绪，请检查中文语音引擎。"); onDone(); return }
        val now=SystemClock.elapsedRealtime()
        val ticket=SpeechTicket("speech-${System.nanoTime()}",key,text,priority,now,now+validForMs)
        callbacks[ticket.id]=onDone
        apply(arbiter.offer(ticket,now))
    }

    private fun finishSpeech(id: String?) {
        handler.post {
            if(id==null) return@post
            val transition=arbiter.complete(id,SystemClock.elapsedRealtime())
            finishTicket(id)
            apply(transition)
        }
    }

    private fun apply(transition: SpeechTransition) {
        transition.discarded.forEach { finishTicket(it.id) }
        if(transition.stopCurrent) tts.stop()
        transition.start?.let { ticket ->
            val timeout=Runnable { finishSpeech(ticket.id) }
            timeouts[ticket.id]=timeout; handler.postDelayed(timeout,30_000)
            if(tts.speak(ticket.text,TextToSpeech.QUEUE_FLUSH,null,ticket.id)==TextToSpeech.ERROR) {
                onStatus("系统语音播放失败，请检查媒体音量和语音引擎")
                finishSpeech(ticket.id)
                return
            }
        }
        publishBusy()
    }

    /** Keeps the published state in step with whatever the arbiter now holds. */
    private fun publishBusy() { mutableBusy.value = dialogActive || arbiter.isBusy() }

    private fun finishTicket(id: String) {
        timeouts.remove(id)?.let(handler::removeCallbacks)
        callbacks.remove(id)?.invoke()
    }

    fun cancelDialog() {
        dialogActive = false
        apply(arbiter.cancelNonEmergency())
    }

    private fun vibrate(level: RiskLevel) {
        val deviceVibrator = vibrator ?: return
        if (!deviceVibrator.hasVibrator()) return
        val timings = when (level) {
            RiskLevel.DANGER -> longArrayOf(0, 180, 80, 180)
            RiskLevel.WARNING -> longArrayOf(0, 100, 100, 100)
            RiskLevel.NOTICE -> longArrayOf(0, 120)
            RiskLevel.SAFE -> return
        }
        val amplitudes = IntArray(timings.size) { index -> if (index % 2 == 1) 255 else 0 }
        deviceVibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
    }

    override fun close() { apply(arbiter.clear()); vibrator?.cancel(); tts.shutdown() }
}
