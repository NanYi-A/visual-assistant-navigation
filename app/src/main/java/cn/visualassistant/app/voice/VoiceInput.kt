package cn.visualassistant.app.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Embedded Chinese ASR; microphone and native model are owned by one worker. */
class VoiceInput(
    context: Context,
    private val onStatus: (String) -> Unit,
    private val onResult: (String) -> Unit,
    private val onFailure: (String) -> Unit,
) : AutoCloseable {
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val generation = AtomicInteger()
    private var model: Model? = null // Worker only.
    private var closed = false // Main thread only.

    /**
     * True from the moment [start] is called until the worker has released the microphone.
     *
     * The activity must use this to hold its tongue: a status prompt spoken while the recogniser
     * is listening is picked up *by that recogniser*, so the user hears their own prompt come back
     * as a bogus command. This was the single biggest cause of "it keeps dropping me mid-sentence".
     */
    @Volatile
    var isListening: Boolean = false
        private set

    /**
     * Loudest 100 ms frame seen during the last turn, as a percentage of the full scale.
     *
     * Purely diagnostic. A level gate that discards quiet speech was tried and removed: a blind
     * user speaking softly from a metre away was losing real answers, which is a far worse failure
     * than occasionally transcribing a prompt.
     */
    @Volatile
    var lastPeakPercent: Int = 0
        private set

    fun start(prompt:String="请说：前面有什么（离线）") {
        if (closed) return
        val token = generation.incrementAndGet()
        isListening = true
        onStatus("正在准备离线语音…")
        worker.execute {
            var audio: AudioRecord? = null
            var decoder: Recognizer? = null
            var result = ""
            var lastPartial = ""
            var failure: String? = null
            var preparing = true
            // Declared out here so the log line after the catch block can still read them.
            var loudFrames = 0
            var peakRms = 0
            try {
                if (token != generation.get()) return@execute
                if (model == null) model = Model(prepareModel().absolutePath)
                if (token != generation.get()) return@execute
                decoder = Recognizer(model!!, 16000f)
                preparing = false
                val minimum = AudioRecord.getMinBufferSize(16000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minimum > 0) { "Unsupported microphone sample rate: $minimum" }
                audio = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minimum, 6400))
                check(audio.state == AudioRecord.STATE_INITIALIZED) { "Microphone initialization failed" }
                audio.startRecording()
                check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone did not start" }
                post(token) { onStatus(prompt) }
                val samples = ShortArray(1600)
                val deadline = SystemClock.elapsedRealtime() + LISTEN_MS
                // Once something has been heard we keep the microphone open for a short settle
                // window instead of cutting the user off mid-word. The recogniser refines its
                // hypothesis as more audio arrives, so stopping on the first hit is what produced
                // "我要" for "我要去卫生间".
                var settleUntil = 0L
                while (token == generation.get() && SystemClock.elapsedRealtime() < deadline) {
                    // Nonblocking reads keep cancellation responsive even with a stalled audio source.
                    val count = audio.read(samples, 0, samples.size, AudioRecord.READ_NON_BLOCKING)
                    check(count >= 0) { "Microphone read failed: $count" }
                    if (count == 0) {
                        Thread.sleep(20)
                        continue
                    }
                    val level = rms(samples, count)
                    if (level > peakRms) peakRms = level
                    if (level >= VOICE_RMS) loudFrames++
                    if (decoder.acceptWaveForm(samples, count)) {
                        val segment = transcript(decoder.result)
                        if (segment.isNotBlank()) {
                            // A strictly longer hypothesis is a better one; keep the longest.
                            if (segment.length > result.length) result = segment
                            lastPartial = segment
                            settleUntil = SystemClock.elapsedRealtime() + SETTLE_MS
                        }
                    } else {
                        val partial = transcript(decoder.getPartialResult())
                        if (partial.isNotBlank()) {
                            lastPartial = partial
                            if (partial.length > result.length) result = partial
                        }
                    }
                    if (settleUntil > 0 && SystemClock.elapsedRealtime() >= settleUntil) break
                }
                if (token == generation.get()) {
                    val final = transcript(decoder.finalResult)
                    if (final.isNotBlank()) result = final
                    if (result.isBlank()) result = lastPartial
                }
            } catch (e: Exception) {
                Log.e("VoiceInput", "Offline speech recognition failed", e)
                failure = if (e is SecurityException) "需要麦克风权限才能提问。"
                    else if (preparing) "离线中文模型加载失败，请检查手机存储空间并重新打开应用。"
                    else "离线语音启动失败，请关闭占用麦克风的应用后重试。若仍失败，请重新打开应用。"
            } finally {
                // Release the microphone before a result can trigger speech playback.
                audio?.let {
                    runCatching { if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop() }
                    it.release()
                }
                decoder?.close()
            }
            val heard = result
            val reported = failure
            val peak = (peakRms * 100 / 32768)
            lastPeakPercent = peak
            Log.i("VoiceInput", "turn finished: token=$token heard=\"$heard\" failure=$reported peak=${peak}% loudFrames=$loudFrames")
            handler.post {
                isListening = false
                // A token that no longer matches means the user (or a screen change) cancelled this
                // turn. Its callbacks are deliberately not delivered — that is how the old code
                // silently swallowed every attempt, leaving the user with no reply at all.
                if (token != generation.get()) return@post
                when {
                    reported != null -> onFailure(reported)
                    heard.isBlank() -> onFailure("没有听清，请点击语音提问重试。")
                    else -> onResult(heard)
                }
            }
        }
    }

    private fun transcript(json: String) = JSONObject(json).optString("text")
        .ifBlank { JSONObject(json).optString("partial") }
        .filterNot(Char::isWhitespace)

    /** Root-mean-square level of one 100 ms frame, as the recogniser sees it. */
    private fun rms(buffer: ShortArray, count: Int): Int {
        var sum = 0L
        for (i in 0 until count) { val v = buffer[i].toInt(); sum += (v * v).toLong() }
        return kotlin.math.sqrt(sum.toDouble() / count).toInt()
    }

    private fun post(token: Int, action: () -> Unit) {
        handler.post { if (token == generation.get()) action() }
    }

    private fun prepareModel(): File {
        val name = "vosk-model-small-cn-0.22"
        val target = File(app.noBackupFilesDir, name)
        val ready = File(target, ".complete")
        if (!ready.isFile) {
            copyAssets(name, target)
            ready.writeText("0.22")
        }
        return target
    }

    private fun copyAssets(path: String, target: File) {
        val children = app.assets.list(path).orEmpty()
        if (children.isEmpty()) {
            app.assets.open(path).use { input -> target.outputStream().use { input.copyTo(it) } }
        } else {
            check(target.isDirectory || target.mkdirs()) { "Cannot create model directory" }
            children.forEach { copyAssets("$path/$it", File(target, it)) }
        }
    }

    fun cancel() { generation.incrementAndGet() }

    override fun close() {
        if (closed) return
        closed = true
        cancel()
        worker.execute { model?.close(); model = null }
        worker.shutdown()
    }

    private companion object {
        /** Long enough for a blind user to say "我要去卫生间" plus a pause, short enough to feel responsive. */
        const val LISTEN_MS = 20_000L
        /** Extra time after the first hypothesis so the tail of the sentence is not cut off. */
        const val SETTLE_MS = 1_600L
        /** Diagnostic threshold only — never used to discard a transcript. */
        const val VOICE_RMS = 900
    }
}
