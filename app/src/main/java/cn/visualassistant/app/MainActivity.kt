package cn.visualassistant.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.ar.core.ArCoreApk
import cn.visualassistant.app.feedback.FeedbackController
import cn.visualassistant.app.ui.AssistantScreen
import cn.visualassistant.app.voice.VoiceInput
import cn.visualassistant.app.core.RiskLevel

/**
 * Startup router. Runs the ARCore capability check once, then either hands over to the
 * fused spatial page or stays on the CameraX-only local recognition fallback.
 * CameraX is deliberately not bound until the check resolves, because ARCore needs the
 * camera for itself and the two cannot hold it at the same time.
 */
class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel>()
    private lateinit var feedback: FeedbackController
    private lateinit var voiceInput: VoiceInput
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var checking by mutableStateOf(true)
    private var checkMessage by mutableStateOf("正在检查手机是否支持空间扫描…")
    /** True once we handed over to the spatial page; this Activity is kept underneath. */
    private var forwarded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        feedback = FeedbackController(this) { message ->
            runOnUiThread {
                viewModel.setStatus(message)
                if ("失败" in message || "缺少" in message || "尚未" in message) {
                    viewModel.setVoice(false, message)
                }
            }
        }
        voiceInput = VoiceInput(
            this,
            onStatus = { viewModel.setVoice(true, it) },
            onResult = { question ->
                val answer = viewModel.answer(question)
                viewModel.setVoice(true, "你问：$question\n$answer")
                feedback.speakAnswer(answer) {
                    viewModel.setVoice(false, viewModel.state.value.voiceMessage)
                }
            },
            onFailure = {
                feedback.cancelDialog()
                viewModel.setVoice(false, it)
            },
        )
        setContent {
            if (checking) {
                MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFB7F4D2))) {
                    Box(Modifier.fillMaxSize().background(Color(0xFF101418)),
                        contentAlignment = Alignment.Center) {
                        Text(checkMessage, Modifier.padding(32.dp), color = Color.White,
                            style = MaterialTheme.typography.bodyLarge)
                    }
                }
            } else {
            val state by viewModel.state.collectAsStateWithLifecycle()
            val context = LocalContext.current
            val microphoneLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { granted ->
                if (granted) startQuestion()
                else viewModel.setVoice(false, "未获得麦克风权限，请在系统设置中允许后再提问。")
            }
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { granted ->
                viewModel.setStatus(if (granted) "正在启动相机" else "需要相机权限；请在系统设置中授权")
            }
            LaunchedEffect(Unit) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                }
            }
            AssistantScreen(
                state = state,
                onDetections = { items, elapsed ->
                    runOnUiThread {
                        viewModel.onDetections(items, elapsed)?.let { assessment ->
                            if (assessment.level == RiskLevel.DANGER && viewModel.state.value.voiceBusy) {
                                cancelQuestion("风险提醒优先，问答已结束。")
                            }
                            feedback.alert(assessment)
                        }
                    }
                },
                onStatus = viewModel::setStatus,
                onSpatialScan = {
                    cancelQuestion("")
                    startActivity(spatialIntent(autoStart = false))
                },
                onToggleCamera = {
                    cancelQuestion("")
                    viewModel.toggleCamera()
                },
                onVoiceQuestion = {
                    if (viewModel.state.value.voiceBusy) cancelQuestion("")
                    else if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        startQuestion()
                    } else microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)
                },
            )
            }
        }
        if (savedInstanceState == null) checkSpatialSupport()
    }

    /**
     * One capability check per launch. Anything other than "supported" keeps the user on
     * the local recognition page with a stated reason; the check must not block startup.
     */
    private fun checkSpatialSupport() {
        val startedAt = SystemClock.elapsedRealtime()
        val timeout = Runnable {
            if (!checking) return@Runnable
            checkMessage = "设备支持检查超时，已切换到本地识别"
            android.util.Log.w("SpatialScan", "startup capability check timed out")
            stayOnLocalRecognition()
        }
        handler.postDelayed(timeout, 15_000)
        ArCoreApk.getInstance().checkAvailabilityAsync(this) { availability ->
            if (!checking || isFinishing) return@checkAvailabilityAsync
            handler.removeCallbacks(timeout)
            android.util.Log.i("SpatialScan",
                "startup capability=$availability device=${android.os.Build.MODEL} in ${SystemClock.elapsedRealtime()-startedAt}ms")
            if (availability.isSupported) enterSpatialMode()
            else {
                checkMessage = when (availability) {
                    ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE ->
                        "这台手机不支持 ARCore 空间定位，已切换到本地识别"
                    else -> "暂时无法确认是否支持空间扫描，已切换到本地识别"
                }
                stayOnLocalRecognition()
            }
        }
    }

    private fun enterSpatialMode() {
        // Deliberately leaves `checking` true so the CameraX page never composes (and never
        // grabs the camera) before ARCore opens its own session. The local page is composed
        // in onResume instead, i.e. only once the user actually comes back here.
        forwarded = true
        startActivity(spatialIntent(autoStart = true))
    }

    /** Debug builds may receive a LAN backend through the exported launcher, while the
     * internal spatial Activity remains non-exported. Release builds ignore these extras. */
    private fun spatialIntent(autoStart: Boolean): Intent =
        Intent(this, cn.visualassistant.app.spatial.SpatialActivity::class.java).apply {
            putExtra("autoStart", autoStart)
            if (BuildConfig.DEBUG) {
                intent?.getStringExtra("online3dHost")?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    putExtra("online3dHost", it)
                    putExtra("online3dPort", intent?.getIntExtra("online3dPort", 38400) ?: 38400)
                }
            }
        }

    override fun onResume() {
        super.onResume()
        if (forwarded && checking) {
            checking = false
            viewModel.setStatus("已返回本地识别")
        }
    }

    private fun stayOnLocalRecognition() {
        checking = false
        viewModel.setStatus(checkMessage)
    }

    private fun startQuestion() {
        if (!viewModel.state.value.cameraRunning) {
            viewModel.setVoice(false, "请先继续检测，再提问。")
            return
        }
        feedback.beginDialog()
        viewModel.setVoice(true, "正在启动语音…")
        voiceInput.start()
    }

    private fun cancelQuestion(message: String) {
        voiceInput.cancel()
        feedback.cancelDialog()
        viewModel.setVoice(false, message)
    }

    override fun onStop() {
        cancelQuestion("")
        viewModel.resetScene()
        super.onStop()
    }

    override fun onDestroy() { voiceInput.close(); feedback.close(); super.onDestroy() }
}
