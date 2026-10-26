package cn.visualassistant.app.ui

import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.visualassistant.app.AssistantUiState
import cn.visualassistant.app.camera.FrameAnalyzer
import cn.visualassistant.app.core.Detection
import cn.visualassistant.app.core.RiskLevel
import cn.visualassistant.app.inference.YoloDetector
import java.util.concurrent.Executors

@Composable
fun AssistantScreen(
    state: AssistantUiState,
    onDetections: (List<Detection>, Long) -> Unit,
    onStatus: (String) -> Unit,
    onToggleCamera: () -> Unit,
    onVoiceQuestion: () -> Unit,
    onSpatialScan: () -> Unit,
) {
    val context = LocalContext.current
    val detector = remember {
        runCatching { YoloDetector(context) }.onFailure { onStatus("检测模型加载失败：${it.message}") }.getOrNull()
    }
    DisposableEffect(Unit) { onDispose { detector?.close() } }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Color(0xFFB7F4D2),
        onPrimary = Color(0xFF073923),
        surface = Color(0xFF141E22),
        onSurface = Color(0xFFF3F7F5),
    )) {
    Box(Modifier.fillMaxSize().background(Color(0xFF101418))) {
        if (context.checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED && detector != null) {
            CameraPreview(
                running = state.cameraRunning,
                detector = detector,
                onDetections = onDetections,
                onStatus = onStatus,
                modifier = Modifier.fillMaxSize(),
            )
            DetectionOverlay(state.detections)
        }

        Column(
            Modifier.fillMaxWidth().align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onSpatialScan) { Text("空间扫描", color = Color.White) }
                Spacer(Modifier.weight(1f))
                Surface(color = Color(0xE619332A), shape = RoundedCornerShape(50),
                    border = BorderStroke(1.dp, Color(0xFF426856))) {
                    Text("本地识别", Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        color = Color(0xFFB7F4D2), style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        if (!state.cameraRunning) {
            Surface(modifier = Modifier.align(Alignment.Center), color = Color(0xE6141E22),
                shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("检测已暂停", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("点击下方按钮继续观察", color = Color(0xFFC0CECA))
                }
            }
        }

        val accent = when {
            !state.cameraRunning -> Color(0xFFC0CECA)
            state.risk.level == RiskLevel.DANGER -> Color(0xFFFFA49D)
            state.risk.level == RiskLevel.WARNING -> Color(0xFFFFCB91)
            state.risk.level == RiskLevel.NOTICE -> Color(0xFFF5DF91)
            else -> Color(0xFFB7F4D2)
        }
        Column(
            modifier = Modifier.align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.heightIn(max = 120.dp).verticalScroll(rememberScrollState())) {
                Text(
                    state.voiceMessage.ifBlank {
                        if (state.cameraRunning) state.risk.message else "检测已暂停"
                    },
                    color = accent, style = MaterialTheme.typography.bodyLarge.copy(
                        shadow = Shadow(Color.Black, Offset(0f, 2f), 6f),
                    ),
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = onVoiceQuestion,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(if (state.voiceBusy) "取消问答" else "语音提问",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            shadow = Shadow(Color.Black, Offset(0f, 2f), 6f),
                        ))
                }
                TextButton(
                    onClick = onToggleCamera,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    modifier = Modifier.heightIn(min = 48.dp).semantics {
                        contentDescription = if (state.cameraRunning) "暂停检测" else "继续检测"
                    },
                ) { Text(if (state.cameraRunning) "暂停检测" else "继续检测",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        shadow = Shadow(Color.Black, Offset(0f, 2f), 6f),
                    )) }
            }
        }
    }
    }
}

@Composable
private fun CameraPreview(
    running: Boolean,
    detector: YoloDetector,
    onDetections: (List<Detection>, Long) -> Unit,
    onStatus: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    DisposableEffect(running, lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            runCatching {
                val provider = future.get()
                provider.unbindAll()
                if (running) {
                    val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .setTargetResolution(android.util.Size(640, 480))
                        .build()
                        .also { it.setAnalyzer(executor, FrameAnalyzer(detector, onDetections) { e -> onStatus("检测失败：${e.message}") }) }
                    val camera = provider.bindToLifecycle(
                        lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis,
                    )
                    // Apply after every bind, including resume from the pause button.
                    // Some vendors do not expose their ultrawide camera through CameraX.
                    camera.cameraInfo.zoomState.value?.let { zoom ->
                        val targetRatio = 0.6f.coerceIn(zoom.minZoomRatio, zoom.maxZoomRatio)
                        val zoomRequest = camera.cameraControl.setZoomRatio(targetRatio)
                        zoomRequest.addListener({
                            runCatching { zoomRequest.get() }.onFailure {
                                android.util.Log.w("VisualAssistant", "Default zoom unavailable", it)
                            }
                        }, ContextCompat.getMainExecutor(context))
                    }
                    onStatus("离线检测中")
                } else {
                    onStatus("检测已暂停")
                }
            }.onFailure { onStatus("相机启动失败：${it.message}") }
        }, ContextCompat.getMainExecutor(context))
        onDispose { if (future.isDone) runCatching { future.get().unbindAll() } }
    }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }
    AndroidView(factory = { previewView }, modifier = modifier)
}

@Composable
private fun DetectionOverlay(detections: List<Detection>) {
    Canvas(Modifier.fillMaxSize()) {
        // Analysis frames are portrait 480x640. Match PreviewView.FILL_CENTER crop.
        val sourceWidth = 480f
        val sourceHeight = 640f
        val scale = maxOf(size.width / sourceWidth, size.height / sourceHeight)
        val renderedWidth = sourceWidth * scale
        val renderedHeight = sourceHeight * scale
        val offsetX = (size.width - renderedWidth) / 2f
        val offsetY = (size.height - renderedHeight) / 2f
        detections.forEach { detection ->
            val box = detection.box
            drawRect(
                color = Color(0xFFFFD54F),
                topLeft = Offset(offsetX + box.left * renderedWidth, offsetY + box.top * renderedHeight),
                size = Size(box.width * renderedWidth, box.height * renderedHeight),
                style = Stroke(width = 5f),
            )
        }
    }
}
