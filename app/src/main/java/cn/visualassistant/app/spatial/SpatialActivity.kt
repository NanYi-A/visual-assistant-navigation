package cn.visualassistant.app.spatial

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.ar.core.*
import cn.visualassistant.app.feedback.FeedbackController
import cn.visualassistant.app.feedback.SpeechPriority
import cn.visualassistant.app.voice.VoiceInput
import cn.visualassistant.app.MainViewModel
import cn.visualassistant.app.core.Detection
import cn.visualassistant.app.core.RiskLevel
import cn.visualassistant.app.online3d.Online3dClient
import cn.visualassistant.app.online3d.RemoteVoxelMap
import cn.visualassistant.app.online3d.DeltaResult
import cn.visualassistant.app.online3d.RemoteVoxel
import cn.visualassistant.app.online3d.RemoteVoxelState
import cn.visualassistant.app.online3d.RemotePose3
import cn.visualassistant.app.online3d.RemoteMapQualityAnalyzer
import cn.visualassistant.app.online3d.RemoteMesh
import cn.visualassistant.app.online3d.RemoteTriangle
import cn.visualassistant.app.online3d.Color3dClient
import cn.visualassistant.app.online3d.RemoteColoredMesh
import cn.visualassistant.app.online3d.RemoteColoredTriangle
import cn.visualassistant.app.online3d.ConfirmedPlacePacket
import cn.visualassistant.app.online3d.RemoteModelStatus
import kotlin.math.cos
import kotlin.math.sin
import cn.visualassistant.app.home.HomePresenceVerifier
import cn.visualassistant.app.home.AmapHomeLocator
import cn.visualassistant.app.home.GeoFix
import cn.visualassistant.app.home.HomeGeoAnchor
import cn.visualassistant.app.home.HomeGeoPresence

/** How many times a failed navigation turn is re-asked before giving up and telling the user why. */
private const val NAV_RETRY_LIMIT = 2
/** Silence after a prompt before the microphone opens, so the prompt is not transcribed back. */
private const val SPEAKER_SETTLE_MS = 700L
/** Placeholder address used until the computer has been found on the network. */
private const val PENDING_HOST = "0.0.0.0"
/** How long to wait for one address to answer before moving on. */
private const val PROBE_TIMEOUT_MS = 250
/** Do not hammer the network: one automatic search per minute is enough. */
private const val DISCOVERY_COOLDOWN_MS = 60_000L

class SpatialActivity : ComponentActivity() {
    /** Same short-term memory, avoidance and recovery rules as the CameraX-only page. */
    private val viewModel by viewModels<MainViewModel>()
    private var snapshot by mutableStateOf(SpatialSnapshot())
    private var labels by mutableStateOf(listOf<String>())
    private var message by mutableStateOf("")
    private var capability by mutableStateOf("正在检查手机空间扫描能力…")
    private var supported by mutableStateOf(false)
    private var scanning by mutableStateOf(false)
    private var scanMode by mutableStateOf(ScanMode.OUTDOOR)
    /** Read in onCreate: `intent` is still null while properties are being initialised. */
    private var wantsScan = false
    private var userRequestedInstall = true
    private var foreground = false
    private var glRunning = false
    private var pausedForLifecycle = false
    private lateinit var surface: GLSurfaceView
    private lateinit var renderer: SpatialRenderer
    private lateinit var feedback: FeedbackController
    private lateinit var voice: VoiceInput
    private var online3dClient: Online3dClient? = null
    private var color3dClient: Color3dClient? = null
    private val remoteVoxelMap=RemoteVoxelMap()
    private var remoteMapStatus by mutableStateOf("")
    private var remoteVoxels by mutableStateOf<List<RemoteVoxel>>(emptyList())
    private var remoteVoxelSize by mutableFloatStateOf(.08f)
    private var remoteCameraPose by mutableStateOf<RemotePose3?>(null)
    private var remoteTrajectory by mutableStateOf<List<RemotePose3>>(emptyList())
    private var remoteQualityStatus by mutableStateOf("")
    private var remoteMesh by mutableStateOf<RemoteMesh?>(null)
    private var remoteColoredMesh by mutableStateOf<RemoteColoredMesh?>(null)
    private var colorModelStatus by mutableStateOf("彩色模型尚未连接电脑")
    private lateinit var modelLibrary:HomeModelLibrary
    private var modelEntries by mutableStateOf<List<HomeModelEntry>>(emptyList())
    private var showModelLibrary by mutableStateOf(false)
    private var selectedLibraryEntry by mutableStateOf<HomeModelEntry?>(null)
    private var libraryMesh by mutableStateOf<cn.visualassistant.app.online3d.RemoteColoredMesh?>(null)
    private var libraryOptimizedMesh by mutableStateOf<cn.visualassistant.app.online3d.RemoteColoredMesh?>(null)
    private var optimizedTransferStatus:RemoteModelStatus?=null
    private var libraryCognition by mutableStateOf<SavedCognition?>(null)
    private var libraryPlaces by mutableStateOf<List<HomePlace>>(emptyList())
    private var activeModelId:String?=null
    private var pendingParentModelId:String?=null
    private var localizationParentModelId:String?=null
    private var locatingHome by mutableStateOf(false)
    private var localizationAttempt=0
    private var locatingSessionId:Long?=null
    /** Verified transform from this live ARCore session into the persistent home-map frame. */
    private var localizedHomeTransform:HomeMapPoseTransform?=null
    /** True after the finite localization observation has been closed on the computer. */
    private var localizationSubmitted=false
    /** The 12 second observation window starts only after two usable RGB-D frames. */
    private var localizationValidDepthFrames=0
    private var localizationObservationStarted=false
    private var homeBackendHost=""
    private var homeBackendPort=38401
    private var onlineBackendPort=38400
    /** The computer's address on the phone's network; it moves whenever it rejoins the hotspot. */
    private var backendAddress by mutableStateOf("")
    private var discoveryStatus by mutableStateOf("")
    private var discoveryRunning=false
    private var lastDiscoveryAt=0L
    private var startupPresencePending=false
    private var pendingGeoOperation:GeoOperation?=null
    private var pendingNewHomeFix:GeoFix?=null
    private var placeDialogStage=PlaceDialogStage.NONE
    private var pendingPlaceName=""
    private var pendingPlacePose:RemotePose3?=null
    private var microphoneAction=MicrophoneAction.QUESTION
    private var lastQualityNoticeAt=0L
    private var lastUsable: Boolean? = null
    private var lastHazard = 0L
    private var silenceUntil = 0L
    private var guiding by mutableStateOf(false)
    private var lastGuidanceText = ""
    private var lastGuidanceAt = 0L
    private var scanStartedAt = 0L
    private var lastOverheadReminderAt = 0L
    private val prompts = SpatialVoicePrompts()
    /** Destination navigation over the persisted home map. Lives with the ARCore session because
     *  it needs the live pose, the live grid and the model id that only exist here. */
    private val navSession = HomeNavigationSession()
    /** Set when navigation was asked for before a session existed, so the prompt follows the scan. */
    private var pendingDestinationPrompt=false
    private var homeNavMap: HomeMap? = null
    private var homeNavStatus by mutableStateOf("")
    /** Explicit opt-in for a sighted companion to evaluate the legacy 2D candidate route. */
    private var supervisedNavigationTest by mutableStateOf(false)
    private var supervisedRouteDisclaimerSpoken = false
    /** What the open microphone is currently waiting for; drives the retry prompt. */
    private var navListeningFor: NavPrompt? = null
    private var navPromptRetries = 0
    /** Set when a hazard spoke over an open turn; that transcript is a mix of two voices. */
    @Volatile private var voiceTurnPolluted = false
    private var pendingNotice: String? = null
    private val watchdog = FrameWatchdog()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var capabilityAttempt = 0
    private val voiceTick = object : Runnable {
        override fun run() {
            if (!foreground) return
            val now=SystemClock.elapsedRealtime()
            // Capture data watchdog runs even when TTS is not ready; speech
            // readiness must not decide whether a stalled capture is ended.
            if (scanning) {
                when (watchdog.evaluate(now, renderer.lastFrameAtMs)) {
                    FrameWatchdog.Action.WARN -> {
                        snapshot=snapshot.copy(marks=emptyList(),status="相机画面中断，正在尝试恢复",usable=false,phase=ScanPhase.WAITING_DEPTH)
                        if(pendingNotice==null) pendingNotice="相机画面中断，正在尝试恢复"
                    }
                    FrameWatchdog.Action.STOP -> {
                        watchdog.reset()
                        snapshot=snapshot.copy(marks=emptyList(),status="相机画面持续中断，请重新开始扫描",usable=false,phase=ScanPhase.ERROR)
                        pendingNotice=snapshot.status
                    }
                    FrameWatchdog.Action.NONE -> {}
                }
            }
            // While the recogniser holds the microphone nothing may be spoken except an emergency.
            // The phone speaker is right next to the mic: a status prompt played during a listening
            // turn is transcribed as the user's own words, which is exactly how "我要去卫生间" turned
            // into a bogus command and how the user lost the prompt before finishing a sentence.
            if (feedback.isReady && placeDialogStage==PlaceDialogStage.NONE && !voice.isListening) {
                // Read the scheduler, not a local flag: a queued message replaced by a
                // newer one must still count as busy until it has actually been said.
                val speaking=feedback.busy.value
                if(pendingDestinationPrompt && scanning && snapshot.usable && !speaking){
                    // Wait for a usable pose before asking, otherwise the user names a destination
                    // and the very next step fails for lack of a position to plan from.
                    pendingDestinationPrompt=false
                    startDestinationVoicePrompt()
                } else if (!speaking && now>=silenceUntil && pendingNotice!=null) {
                    val notice=pendingNotice!!; pendingNotice=null; say(notice)
                } else if(scanning&&scanMode==ScanMode.HOME&&!speaking&&!guiding&&now-scanStartedAt>=30_000&&now-lastOverheadReminderAt>=90_000){
                    lastOverheadReminderAt=now
                    say("请补扫头部空间：将手机缓慢抬高，扫描门框顶部、吊柜、横梁和一米八高度的通道",SpeechPriority.STATUS,"overhead-scan")
                } else if (scanning && guiding && !speaking && now>=silenceUntil && navListeningFor==null &&
                    (snapshot.guidance.text!=lastGuidanceText || now-lastGuidanceAt>=8000)) {
                    lastGuidanceText=snapshot.guidance.text; lastGuidanceAt=now
                    say(snapshot.guidance.text,SpeechPriority.NAVIGATION,"navigation")
                } else if (!speaking && now>=silenceUntil) {
                    // Destination guidance is not gated on `scanning`: the route comes from the
                    // stored map, so it keeps working after the capture itself has been stopped.
                    val text=advanceHomeNavigation(now)
                    if(text!=null) say(text,SpeechPriority.NAVIGATION,"home-nav")
                    // Scan chatter is what the user perceives as "点云一直在跳": "空间更新",
                    // "空间扫描已可用" and friends arrive every few seconds and bury the dialogue.
                    // While a destination is being collected the only voice allowed is the dialogue.
                    else if(scanning&&navListeningFor==null) prompts.next(snapshot,now,speaking || guiding || now < silenceUntil)?.let { say(it) }
                    else pendingNotice?.let { pendingNotice=null; say(it) }
                } else if (scanning&&navListeningFor==null) {
                    prompts.next(snapshot,now,speaking || guiding || now < silenceUntil)?.let { say(it) }
                } else if(!speaking && now>=silenceUntil) pendingNotice?.let { pendingNotice=null; say(it) }
            }
            handler.postDelayed(this,500)
        }
    }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) startScan() else { message="需要相机权限才能扫描"; pendingNotice=message; wantsScan=false; cancelSupervisedNavigationTest(message) }
    }
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) {if(microphoneAction==MicrophoneAction.PLACE)beginPlaceLabel() else ask()} else message="需要麦克风权限才能使用语音"
    }
    private val locationPermission=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){
        val granted=checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED
        if(granted)runPendingGeoOperation() else failGeoOperation("没有定位权限，无法自动判断是否在家")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startupPresencePending = intent?.getBooleanExtra("autoStart", false) == true
        wantsScan=false
        modelLibrary=HomeModelLibrary(this);modelLibrary.markInterruptedScans();modelEntries=modelLibrary.load()
        // Opening this page, including auto-start, is always ephemeral outdoor mode.
        // Persistent home mapping requires the dedicated, explicit button below.
        scanMode = ScanMode.OUTDOOR
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        feedback = FeedbackController(this) { text ->
            if ("失败" in text || "缺少" in text) runOnUiThread { message=text }
        }
        voice = VoiceInput(this, onStatus={ message=it },
            onResult=::handleVoiceResult,
            onFailure={ text ->
                message=text
                placeDialogStage=PlaceDialogStage.NONE;pendingPlaceName="";pendingPlacePose=null
                feedback.cancelDialog()
                android.util.Log.i("SpatialVoice","voice turn failed: $text")
                // Going silent after a failed turn leaves a blind user with no idea whether the app
                // is still listening, so a navigation prompt is re-asked instead of dropped.
                if(navListeningFor!=null)handler.postDelayed({retryNavigationPrompt()},700)
            })
        val connectionPrefs=getSharedPreferences("online_3d_connection",MODE_PRIVATE)
        val intentHost=intent?.getStringExtra("online3dHost")?.trim().orEmpty()
        val onlineHost=intentHost.ifEmpty{connectionPrefs.getString("host","").orEmpty()}
        val onlinePort=if(intent?.hasExtra("online3dPort")==true)intent.getIntExtra("online3dPort",38400) else connectionPrefs.getInt("navigation_port",38400)
        val colorPort=if(intent?.hasExtra("color3dPort")==true)intent.getIntExtra("color3dPort",38401) else connectionPrefs.getInt("color_port",38401)
        homeBackendHost=onlineHost;homeBackendPort=colorPort;onlineBackendPort=onlinePort
        backendAddress=onlineHost
        if(intentHost.isNotEmpty())connectionPrefs.edit().putString("host",intentHost).putInt("navigation_port",onlinePort).putInt("color_port",colorPort).apply()
        // The clients are always started, even without a known address: a placeholder address
        // fails immediately, and the discovery below replaces it as soon as the computer is found.
        val initialHost=onlineHost.ifBlank{ PENDING_HOST }
        run {
            online3dClient = Online3dClient(initialHost, onlinePort, { text ->
                runOnUiThread { if (scanning) message = text
                    if (text.contains("未连接")) scheduleBackendDiscovery() }
            }, { packet -> runOnUiThread {
                val result=if(packet.snapshot) remoteVoxelMap.replaceSnapshot(packet.delta) else remoteVoxelMap.apply(packet.delta)
                remoteMapStatus=when(result){
                    DeltaResult.Applied -> {
                        remoteVoxelSize=packet.voxelSize
                        remoteVoxels=remoteVoxelMap.displayValues(packet.cameraPosition,packet.voxelSize)
                        val pose=RemotePose3(packet.cameraPosition[0],packet.cameraPosition[1],packet.cameraPosition[2],packet.cameraForward[0],packet.cameraForward[1],packet.cameraForward[2])
                        if(packet.snapshot) remoteTrajectory=emptyList()
                        val previous=remoteTrajectory.lastOrNull()
                        if(previous==null || previous.distanceSquared(pose)>=.01f)
                            remoteTrajectory=(remoteTrajectory+pose).takeLast(1_000)
                        remoteCameraPose=pose
                        val quality=RemoteMapQualityAnalyzer.analyze(remoteVoxels,remoteVoxelSize,pose)
                        remoteQualityStatus=quality.displayText
                        if(scanning&&scanMode==ScanMode.HOME&&activeModelId!=null){
                            val modelId=java.lang.Long.toUnsignedString(packet.delta.sessionId)
                            modelLibrary.saveCognition(modelId,remoteVoxelMap.values(),packet.voxelSize,packet.delta.revision){runOnUiThread{modelEntries=modelLibrary.load()}}
                        }
                        val now=SystemClock.elapsedRealtime()
                        if(scanning&&scanMode==ScanMode.HOME&&placeDialogStage==PlaceDialogStage.NONE&&quality.rescanHint!=null&&now-lastQualityNoticeAt>=20_000){
                            pendingNotice=quality.rescanHint;lastQualityNoticeAt=now
                        }
                        "电脑三维地图 ${remoteVoxels.size} 格 · 版本 ${remoteVoxelMap.revision}"
                    }
                    DeltaResult.SnapshotRequired -> { online3dClient?.requestSnapshot();"地图更新不连续，正在请求完整快照" }
                    DeltaResult.WrongSession -> { online3dClient?.requestSnapshot();"地图会话已变化，正在重新同步" }
                    DeltaResult.Stale -> remoteMapStatus
                }
            } }, { mesh -> runOnUiThread {
                val current=remoteMesh
                if(current==null||current.sessionId!=mesh.sessionId||mesh.revision>=current.revision)remoteMesh=mesh
            } })
            colorModelStatus="等待彩色深度数据"
            color3dClient=Color3dClient(initialHost,colorPort,{text->runOnUiThread{colorModelStatus=text;if(scanning&&scanMode==ScanMode.HOME)message=text
                if(text.contains("未连接"))scheduleBackendDiscovery()}},{mesh->runOnUiThread{
                val current=remoteColoredMesh
                if(current==null||current.sessionId!=mesh.sessionId||mesh.revision>=current.revision){remoteColoredMesh=mesh;colorModelStatus="彩色模型已更新 · 版本 ${mesh.revision}"}
                val modelId=java.lang.Long.toUnsignedString(mesh.sessionId)
                if(activeModelId==modelId)modelLibrary.saveScanMesh(modelId,mesh){runOnUiThread{modelEntries=modelLibrary.load()}}
            }},{result->runOnUiThread{
                if(locatingHome&&locatingSessionId==result.sessionId){
                    val parentId=java.lang.Long.toUnsignedString(result.parentModelId)
                    val home=modelEntries.firstOrNull{it.id==parentId}
                    if(result.localized){
                        localizedHomeTransform=HomeMapPoseTransform(parentId,result.parentFromSession.copyOf(),
                            SystemClock.elapsedRealtime(),result.consistentPairs,result.inliers,result.overlap,result.medianErrorMeters)
                        homeNavMap=modelLibrary.loadHomeMap(parentId)
                        locatingHome=false;localizationParentModelId=null
                        message="已定位到${home?.name?:"家庭模型"}，当前位置和朝向已接入真实家地图；现在可以说我要去卫生间"
                        say(message,SpeechPriority.ANSWER,"home-localization")
                    }else{
                        localizedHomeTransform=null;locatingHome=false;localizationParentModelId=null
                        supervisedNavigationTest=false;supervisedRouteDisclaimerSpoken=false;pendingDestinationPrompt=false
                        stopScan()
                        message="家庭定位未能确认，已停止本次定位。请对准以前扫描过的门口或墙角后重试"
                        say(message,SpeechPriority.STATUS,"home-localization")
                    }
                }
            }},{mesh->runOnUiThread{
                val id=java.lang.Long.toUnsignedString(mesh.sessionId);val state=optimizedTransferStatus
                modelLibrary.saveOptimizedMesh(id,mesh,state?.message?:"电脑优化模型已同步",state?.navigationReady==true){runOnUiThread{modelEntries=modelLibrary.load();if(selectedLibraryEntry?.id==id){libraryOptimizedMesh=modelLibrary.loadOptimizedMesh(id);selectedLibraryEntry=modelEntries.firstOrNull{it.id==id}}}}
            }},{state->runOnUiThread{
                optimizedTransferStatus=state;val id=java.lang.Long.toUnsignedString(state.sessionId)
                if(state.available)modelLibrary.updateOptimizedStatus(id,state.message,state.navigationReady) else {modelLibrary.rejectOptimizedMesh(id,state.message);if(selectedLibraryEntry?.id==id)libraryOptimizedMesh=null}
                modelEntries=modelLibrary.load();if(selectedLibraryEntry?.id==id)selectedLibraryEntry=modelEntries.firstOrNull{it.id==id}
            }})
        }
        if (onlineHost.isBlank()) scheduleBackendDiscovery()
        renderer = SpatialRenderer(this, { update -> runOnUiThread {
            if (scanning && foreground) {
                snapshot=update
                if(update.phase in setOf(ScanPhase.LOST,ScanPhase.ERROR)) {
                    guiding=false; lastGuidanceText=""; lastGuidanceAt=0
                }
                val now=SystemClock.elapsedRealtime()
                val spatialRisk=SpatialRiskEngine.assess(update,now)
                    // A navigation dialogue owns the microphone. The hazard channel stays live in the
                    // status line, but it must not speak: every announcement is transcribed straight
                    // back as the user's own words, and because the near-surface rule fires on almost
                    // every frame the user was being shouted at every few seconds and every sentence
                    // was destroyed. Real collision avoidance lives in the immediate obstacle layer.
                    val hazardMaySpeak=navListeningFor==null&&!voice.isListening&&placeDialogStage==PlaceDialogStage.NONE
                if (placeDialogStage==PlaceDialogStage.NONE&&spatialRisk.level!=RiskLevel.SAFE && now-lastHazard > 4000) {
                        lastHazard=now
                        message=spatialRisk.message
                        if(hazardMaySpeak){
                            silenceUntil=now+2500
                            feedback.alert(cn.visualassistant.app.core.RiskAssessment(
                                spatialRisk.level,message=spatialRisk.message))
                        } else {
                            android.util.Log.i("SpatialVoice","hazard suppressed during a voice turn: ${spatialRisk.message}")
                        }
                } else if (placeDialogStage==PlaceDialogStage.NONE&&lastUsable == true && !update.usable) {
                    // A tracking dropout must not take the microphone with it. Losing tracking is a
                    // rendering problem; cancelling here meant every brief ARCore hiccup silently
                    // destroyed whatever the user was saying, which is indistinguishable from the
                    // app being broken.
                    if(voice.isListening)android.util.Log.i("SpatialVoice","tracking lost during a voice turn; microphone kept")
                    else cancelSpeech()
                    message=update.status
                }
                lastUsable=update.usable
            }
        } }, { detections, _ -> runOnUiThread { onDetections(detections) } },
            { frame -> online3dClient?.offer(frame) },
            { frame ->
                // A localization observation is finite.  Continuing to append frames after its
                // FINISH packet makes the backend reopen/mutate the dataset while it is matching.
                if(!(localizationSubmitted&&frame.sessionId==locatingSessionId)) color3dClient?.offer(frame)
                if(locatingHome && frame.sessionId==locatingSessionId)
                    runOnUiThread { onLocalizationDepthFrame(frame.sessionId) }
            })
        surface = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            preserveEGLContextOnPause=true
            setRenderer(renderer)
            renderMode=GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        setContent {
            val speaking by feedback.busy.collectAsStateWithLifecycle()
            MaterialTheme(colorScheme=darkColorScheme(primary=Color(0xFFB7F4D2))) {
            CompositionLocalProvider(LocalContentColor provides Color.White) {
                if(showModelLibrary) HomeModelLibraryPage(modelEntries,selectedLibraryEntry,libraryMesh,libraryOptimizedMesh,libraryCognition,libraryPlaces,
                    onBack={if(selectedLibraryEntry!=null){selectedLibraryEntry=null;libraryMesh=null;libraryOptimizedMesh=null;libraryCognition=null;libraryPlaces=emptyList()}else showModelLibrary=false},
                    onOpen={entry->selectedLibraryEntry=entry;libraryMesh=modelLibrary.loadScanMesh(entry.id);libraryOptimizedMesh=modelLibrary.loadOptimizedMesh(entry.id);libraryCognition=modelLibrary.loadCognition(entry.id);libraryPlaces=modelLibrary.loadPlaces(entry.id);entry.id.toULongOrNull()?.toLong()?.let{color3dClient?.requestOptimized(it)}},
                    onDelete={entry->if(modelLibrary.delete(entry.id)){selectedLibraryEntry=null;libraryMesh=null;libraryOptimizedMesh=null;libraryCognition=null;libraryPlaces=emptyList();modelEntries=modelLibrary.load()}},
                    onResumeScan={entry->pendingParentModelId=entry.parentModelId?:entry.id;selectedLibraryEntry=null;showModelLibrary=false;scanMode=ScanMode.HOME;wantsScan=true;userRequestedInstall=true;checkCapability(true)},
                    onRenamePlace={place,name->selectedLibraryEntry?.let{modelLibrary.renamePlace(it.id,place.id,name);libraryPlaces=modelLibrary.loadPlaces(it.id)}},
                    onDeletePlace={place->selectedLibraryEntry?.let{modelLibrary.deletePlace(it.id,place.id);libraryPlaces=modelLibrary.loadPlaces(it.id)}})
                else Box(Modifier.fillMaxSize().background(Color(0xFF101418))) {
                    AndroidView(factory={surface},modifier=Modifier.fillMaxSize())
                    if(scanning&&scanMode==ScanMode.HOME){
                        val colored=remoteColoredMesh
                        if(colored!=null&&colored.triangles.isNotEmpty())
                            FloatingModelPreview(colored)
                        else Box(Modifier.align(Alignment.TopStart).padding(start=12.dp,top=150.dp).size(156.dp),contentAlignment=Alignment.Center){ModelWaitingPreview(colorModelStatus)}
                    }
                    if(scanning&&snapshot.usable) FloatingMiniMap(snapshot,Modifier.fillMaxSize().safeDrawingPadding())
                    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal=12.dp, vertical=8.dp)) {
                        Surface(shape=RoundedCornerShape(18.dp),color=Color.Transparent,border=BorderStroke(1.dp,Color.Transparent)) {
                            Column(Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=10.dp)) {
                                Row(verticalAlignment=Alignment.CenterVertically) {
                                    Text(if(scanning) scanMode.displayName else "空间助手",color=Color(0xFFB7F4D2),style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f))
                                    if(scanning) Text(if(snapshot.usable) "扫描中" else "等待空间数据",color=Color.White.copy(alpha=.82f),style=MaterialTheme.typography.labelMedium)
                                }
                                Text(if(scanning) snapshot.status else capability,color=Color.White,style=MaterialTheme.typography.bodyMedium,maxLines=2)
                                if(scanning&&scanMode==ScanMode.HOME&&localizedHomeTransform!=null){
                                    val pose=currentLocalizedMapPose()
                                    if(pose==null) Text("地图位姿暂不可用",color=Color(0xFFFFD166),style=MaterialTheme.typography.labelMedium)
                                    else {
                                        val heading=((Math.toDegrees(kotlin.math.atan2(pose.forwardX.toDouble(),-pose.forwardZ.toDouble())).toInt()%360)+360)%360
                                        Text("地图 X ${String.format(java.util.Locale.US,"%.1f",pose.x)} m · Z ${String.format(java.util.Locale.US,"%.1f",pose.z)} m · 朝向 ${heading}°",
                                            color=Color.White.copy(alpha=.88f),style=MaterialTheme.typography.labelMedium)
                                    }
                                }
                                if(scanning&&scanMode==ScanMode.HOME&&remoteQualityStatus.isNotBlank())
                                    Text(remoteQualityStatus,color=Color.White.copy(alpha=.72f),style=MaterialTheme.typography.labelSmall,maxLines=1)
                                if(scanning&&labels.isNotEmpty()) Text(labels.take(3).joinToString(" · "),color=Color.White.copy(alpha=.78f),style=MaterialTheme.typography.labelSmall,maxLines=1)
                                // The computer's address moves whenever it rejoins the phone's hotspot,
                                // so the way to reach it has to be visible and repeatable.
                                Row(verticalAlignment=Alignment.CenterVertically) {
                                    TextButton(onClick={ discoverBackend(true) },contentPadding=PaddingValues(horizontal=8.dp,vertical=0.dp)) {
                                        Text(if(discoveryStatus.isBlank()) "重新搜索电脑" else discoveryStatus,
                                            color=Color.White.copy(alpha=.82f),style=MaterialTheme.typography.labelSmall)
                                    }
                                    if(backendAddress.isNotBlank()) TextButton(onClick={ promptBackendAddress() },contentPadding=PaddingValues(horizontal=8.dp,vertical=0.dp)) {
                                        Text("电脑 $backendAddress",color=Color.White.copy(alpha=.82f),style=MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                        if(!scanning){
                            Spacer(Modifier.weight(1f))
                            Surface(shape=RoundedCornerShape(18.dp),color=Color.Transparent){
                                Column(Modifier.fillMaxWidth().padding(16.dp)){
                                    Text(if(scanMode==ScanMode.OUTDOOR) "户外即时扫描" else "家庭路线采集",color=Color.White,style=MaterialTheme.typography.titleLarge)
                                    Spacer(Modifier.height(6.dp))
                                    Text(if(scanMode==ScanMode.OUTDOOR) "实时查看脚下空间与附近障碍。" else "扫描家庭空间、保存地图，并标记房间地点。",color=Color.White.copy(alpha=.78f),style=MaterialTheme.typography.bodyMedium)
                                    Text("使用 ARCore 空间能力；首次使用可能需要联网。画面仅用于本机空间处理。",color=Color.White.copy(alpha=.58f),style=MaterialTheme.typography.labelSmall,modifier=Modifier.padding(top=8.dp))
                                }
                            }
                        } else Spacer(Modifier.weight(1f))
                        if(message.isNotBlank()) Surface(shape=RoundedCornerShape(14.dp),color=Color(0x33101820),modifier=Modifier.fillMaxWidth().padding(bottom=8.dp)){
                            Text(message,color=Color.White,style=MaterialTheme.typography.bodyMedium,maxLines=2,modifier=Modifier.padding(horizontal=14.dp,vertical=10.dp))
                        }
                        Surface(shape=RoundedCornerShape(22.dp),color=Color.Transparent,border=BorderStroke(1.dp,Color.Transparent)) {
                            Column(Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=6.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                                if(scanning){
                                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly,verticalAlignment=Alignment.CenterVertically){
                                        TextButton(onClick={if(speaking)cancelSpeech() else ask()}){Text(if(speaking)"停止播报" else "语音提问",maxLines=1)}
                                        TextButton(onClick={say(answerQuestion("周围有什么"),SpeechPriority.INFORMATION,"space-description")}){Text("描述空间",maxLines=1)}
                                        TextButton(onClick={guiding=!guiding;lastGuidanceText="";lastGuidanceAt=0;if(guiding)say(snapshot.guidance.text,SpeechPriority.NAVIGATION,"navigation") else {cancelSpeech();message="局部引导已暂停"}}){Text(if(guiding)"暂停引导" else "局部引导",maxLines=1)}
                                    }
                                    if(scanMode==ScanMode.HOME) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly){
                                        TextButton(onClick={beginPlaceLabel()}){Text("标记地点")}
                                        TextButton(onClick={startDestinationVoicePrompt()},enabled=!locatingHome){Text(if(navSession.state is HomeNavigationSession.State.Navigating)"导航状态" else "去标记地点")}
                                    }
                                    if(supervisedNavigationTest) Row(verticalAlignment=Alignment.CenterVertically){
                                        Text("陪同实验 · 路线未认证",color=Color(0xFFFFD166),style=MaterialTheme.typography.labelSmall,modifier=Modifier.weight(1f))
                                        TextButton(onClick={exitSupervisedNavigationTest()}){Text("退出")}
                                    }
                                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly){
                                        Button(onClick={stopScan(userInitiated=true);wantsScan=false;message="扫描已结束";pendingNotice=message},modifier=Modifier.weight(1f).padding(horizontal=4.dp)){Text("结束扫描")}
                                        TextButton(onClick={finish()},modifier=Modifier.weight(1f)){Text("返回")}
                                    }
                                }else{
                                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly){
                                        Button(onClick={scanMode=ScanMode.OUTDOOR;wantsScan=true;userRequestedInstall=true;checkCapability(true)},modifier=Modifier.weight(1f).padding(horizontal=4.dp)){Text("户外扫描")}
                                        Button(onClick={beginNewHomeCollection()},modifier=Modifier.weight(1f).padding(horizontal=4.dp)){Text("采集家庭路线")}
                                    }
                                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly){
                                        TextButton(onClick={beginHomeLocalization()}){Text(if(locatingHome)"正在定位" else "家庭自动定位")}
                                        TextButton(onClick={modelEntries=modelLibrary.load();selectedLibraryEntry=null;libraryMesh=null;libraryCognition=null;libraryPlaces=emptyList();showModelLibrary=true}){Text("模型库 ${modelEntries.size}")}
                                    }
                                    if(modelEntries.any{it.hasHomeMap}) TextButton(onClick={requestSupervisedNavigationTest()}){Text("陪同实验导航")}
                                    if(modelEntries.any{it.parentModelId==null&&it.hasScanModel}) TextButton(onClick={requestGeoOperation(GeoOperation.BIND_EXISTING_MODEL)}){Text("设置家庭位置")}
                                    TextButton(onClick={finish()}){Text("返回本地识别")}
                                }
                            }
                        }
                    }
                }
            }
            }
        }
    }

    private fun checkCapability(start: Boolean) {
        val attempt=++capabilityAttempt
        capability="正在检查手机空间扫描能力…"
        pendingNotice=capability
        handler.postDelayed({
            if(foreground && attempt==capabilityAttempt) {
                capabilityAttempt++
                supported=false; wantsScan=false
                capability="设备支持检查超时，请确认网络和 AR 服务后重试"
                android.util.Log.w("SpatialScan","capabilityTimeout device=${android.os.Build.MODEL}")
                pendingNotice=capability
            }
        },15000)
        ArCoreApk.getInstance().checkAvailabilityAsync(this) { availability ->
            if (!foreground || isFinishing || attempt!=capabilityAttempt) return@checkAvailabilityAsync
            capabilityAttempt++
            supported=availability.isSupported
            capability=when {
                availability.isSupported -> if(availability == ArCoreApk.Availability.SUPPORTED_INSTALLED)
                    "支持空间定位，等待检查深度能力" else "需要安装或更新 Google Play Services for AR"
                availability == ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> "这台手机当前不支持 ARCore 空间定位"
                else -> "暂时无法确认支持情况，请联网后重试"
            }
            android.util.Log.i("SpatialScan","capability=$availability device=${android.os.Build.MODEL}")
            if(start && supported) startScan()
            else { if(start) wantsScan=false; pendingNotice=capability;if(start)cancelSupervisedNavigationTest(capability) }
        }
    }

    private fun startScan() {
        if(!foreground || scanning) return
        if(!locatingHome && !supervisedNavigationTest) localizedHomeTransform=null
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) {
            cameraPermission.launch(Manifest.permission.CAMERA); return
        }
        var candidate: Session? = null
        try {
            if(ArCoreApk.getInstance().requestInstall(this,userRequestedInstall)==ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                userRequestedInstall=false
                message="请完成 AR 服务安装后返回"
                return
            }
            candidate=Session(this)
            renderer.scanMode=scanMode
            val automatic=candidate.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
            val depth=automatic || candidate.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY)
            android.util.Log.i("SpatialScan","sessionReady rawDepth=$depth automatic=$automatic")
            if(!depth) {
                candidate.close(); candidate=null; wantsScan=false
                capability="手机支持定位，但未提供所需深度能力，不能建立本模式的三维表面地图"
                cancelSupervisedNavigationTest(capability)
                pendingNotice=capability; return
            }
            candidate.configure(Config(candidate).apply {
                // Official default depth configuration; raw images and confidence remain our map input.
                depthMode=if(automatic) Config.DepthMode.AUTOMATIC else Config.DepthMode.RAW_DEPTH_ONLY
                focusMode=Config.FocusMode.AUTO
                planeFindingMode=Config.PlaneFindingMode.DISABLED
                lightEstimationMode=Config.LightEstimationMode.DISABLED
            })
            candidate.resume()
            val newModelId=renderer.beginOnlineSession()
            if(locatingHome){locatingSessionId=newModelId.toULongOrNull()?.toLong();localizationSubmitted=false;localizedHomeTransform=null}
            val resumedParent=pendingParentModelId
            if(scanMode==ScanMode.HOME){
                val locationParent=localizationParentModelId
                if(locatingHome&&locationParent!=null){
                    activeModelId=null
                    val childSession=newModelId.toULongOrNull()?.toLong();val parentSession=locationParent.toULongOrNull()?.toLong()
                    if(childSession!=null&&parentSession!=null){online3dClient?.startSegment(childSession,parentSession);color3dClient?.startSegment(childSession,parentSession)}
                }else{
                    activeModelId=newModelId
                    val parent=resumedParent
                    if(parent==null) modelLibrary.create(newModelId) else {
                    modelLibrary.createSegment(newModelId,parent)
                    val childSession=newModelId.toULongOrNull()?.toLong();val parentSession=parent.toULongOrNull()?.toLong()
                    if(childSession!=null&&parentSession!=null){online3dClient?.startSegment(childSession,parentSession);color3dClient?.startSegment(childSession,parentSession)}
                    }
                    if(parent==null)pendingNewHomeFix?.let{fix->modelLibrary.saveGeoAnchor(newModelId,fix.toAnchor());pendingNewHomeFix=null}
                }
                pendingParentModelId=null;modelEntries=modelLibrary.load()
            }
            renderer.imageRotation = getSystemService(android.hardware.camera2.CameraManager::class.java)
                .getCameraCharacteristics(candidate.cameraConfig.cameraId)
                .get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            renderer.session=candidate
            cancelSpeech(); pendingNotice=null; prompts.reset(); silenceUntil=0; lastHazard=0
            guiding=false; lastGuidanceText=""; lastGuidanceAt=0
            watchdog.reset(); renderer.markFrameClock()
            scanning=true; lastUsable=null; snapshot=SpatialSnapshot(SystemClock.elapsedRealtime(),status="正在建立空间定位",phase=ScanPhase.LOCATING)
            scanStartedAt=SystemClock.elapsedRealtime();lastOverheadReminderAt=scanStartedAt-60_000
            message=if(scanMode==ScanMode.HOME)
                if(locatingHome) "正在确认是否在家，请对准旧模型中的门口、墙角或有纹理家具，缓慢左右移动"
                else if(resumedParent!=null) "补扫分段已开始。请先扫描有纹理的旧区域，再扫描缺失区域；电脑确认重定位前不会合并"
                else "家庭建图开始，请缓慢扫描地面、门洞两侧和头部空间；可以长时间扫描，切到后台后返回可继续"
            else "户外即时扫描开始，请平稳持机观察脚前方"
            surface.onResume(); glRunning=true
            if(locatingHome){
                localizationValidDepthFrames=0
                localizationObservationStarted=false
                val attempt=localizationAttempt;val session=locatingSessionId
                message="正在等待可靠深度，获取后再开始 12 秒家庭定位"
                // This is a sensor-readiness timeout, not part of the observation window.
                handler.postDelayed({
                    if(locatingHome&&attempt==localizationAttempt&&session==locatingSessionId&&!localizationObservationStarted){
                        message="60 秒内未获得可靠深度，请对准有纹理的墙角或门口缓慢平移后重试"
                        stopScan()
                    }
                },60_000)
            }
            android.util.Log.i("SpatialScan","scanStarted")
        } catch(e: Exception) {
            candidate?.close(); renderer.session=null; wantsScan=false
            capability=when(e) {
                is com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException -> "AR 服务未完成安装，可再次点击开始扫描"
                is com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException -> "这台手机未通过 ARCore 兼容性检查"
                else -> "无法启动空间扫描（${e.javaClass.simpleName}），请检查 AR 服务后重试"
            }
            android.util.Log.e("SpatialScan",capability,e)
            cancelSupervisedNavigationTest(capability)
            pendingNotice=capability
        }
    }

    private fun onLocalizationDepthFrame(session:Long){
        if(!locatingHome||!scanning||session!=locatingSessionId||localizationObservationStarted)return
        localizationValidDepthFrames++
        if(localizationValidDepthFrames<2){
            message="已收到第一帧深度，正在确认数据连续性"
            return
        }
        localizationObservationStarted=true
        val attempt=localizationAttempt
        val end=SystemClock.elapsedRealtime()+12_000
        val countdown=object:Runnable{override fun run(){
            if(!locatingHome||attempt!=localizationAttempt||session!=locatingSessionId)return
            val left=((end-SystemClock.elapsedRealtime()+999)/1000).coerceAtLeast(0)
            if(left>0){
                message="深度已就绪，正在确认家庭位置，还需观察约 ${left} 秒"
                handler.postDelayed(this,1_000)
            }else if(scanning){
                message="观察完成，电脑正在核对家庭位置"
                // Keep the ARCore session alive: the transform returned by the computer is only
                // useful while this same session continues supplying relative motion.
                localizationSubmitted=true
                color3dClient?.finishHome(session,emptyList())
                handler.postDelayed({if(locatingHome&&attempt==localizationAttempt){
                    // Never leave a blind user waiting indefinitely. The backend has its own
                    // bounded worker timeout; this is a final client-side recovery guard.
                    locatingHome=false;localizedHomeTransform=null;localizationParentModelId=null
                    supervisedNavigationTest=false;supervisedRouteDisclaimerSpoken=false;pendingDestinationPrompt=false
                    stopScan()
                    message="家庭定位超过 35 秒仍未返回，已停止本次定位。请重新尝试；定位成功前不要行走"
                    homeNavStatus=message
                    say(message,SpeechPriority.STATUS,"home-localization-timeout")
                }},35_000)
            }
        }}
        handler.post(countdown)
    }

    private fun beginHomeLocalization(){
        if(scanning||locatingHome)return
        localizedHomeTransform=null
        requestGeoOperation(GeoOperation.MANUAL_HOME_CHECK)
    }

    private fun requestSupervisedNavigationTest(){
        if(scanning||locatingHome){
            say("当前正在扫描或确认家庭位置，请完成或结束当前操作后再开始陪同实验导航",SpeechPriority.ANSWER,"home-nav-test-busy")
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("开始陪同实验导航？")
            .setMessage("当前路线来自旧版二维扫描地图，尚未通过全身净空和通道安全验证。仅可在熟悉、平坦、短距离区域测试；一名清醒的陪同者必须全程观察并能立即扶停。不要独自行走，不测试楼梯、台阶或门槛。继续后会先核实家庭位置，再进行室内定位。")
            .setNegativeButton("取消",null)
            .setPositiveButton("我有陪同，开始测试"){_,_->
                supervisedNavigationTest=true
                supervisedRouteDisclaimerSpoken=false
                pendingDestinationPrompt=true
                beginHomeLocalization()
            }
            .show()
    }

    private fun exitSupervisedNavigationTest(){
        voice.cancel()
        cancelSpeech()
        navSession.cancel("已退出陪同实验导航")
        supervisedNavigationTest=false
        supervisedRouteDisclaimerSpoken=false
        pendingDestinationPrompt=false
        navListeningFor=null
        homeNavStatus="已退出陪同实验导航"
        say(homeNavStatus,SpeechPriority.ANSWER,"home-nav-test-exit")
    }

    private fun cancelSupervisedNavigationTest(reason:String){
        if(!supervisedNavigationTest)return
        supervisedNavigationTest=false;supervisedRouteDisclaimerSpoken=false;pendingDestinationPrompt=false
        navSession.cancel("陪同实验导航未能启动")
        homeNavStatus="陪同实验导航未能启动：$reason"
    }

    private fun supervisedRouteLimitMeters():Float?=if(supervisedNavigationTest)5f else null

    private fun startHomePoseLocalization(parent:HomeModelEntry,presenceDescription:String){
        localizationParentModelId=parent.id;pendingParentModelId=null;scanMode=ScanMode.HOME;wantsScan=true;userRequestedInstall=true
        message="$presenceDescription，已确认家庭候选。现在确认你在家里的具体位置，请对准熟悉的门口或墙角缓慢左右移动"
        say(message,SpeechPriority.ANSWER,"home-presence-ok");checkCapability(true)
    }

    private fun routeStartupByPresence(){
        requestGeoOperation(GeoOperation.STARTUP_HOME_CHECK)
    }

    private fun requestGeoOperation(operation:GeoOperation){
        // A new-model entry must never inherit a stale parent left by a failed
        // relocalization or an interrupted library rescan.
        if(operation==GeoOperation.BIND_NEW_MODEL)pendingParentModelId=null
        pendingGeoOperation=operation
        val privacy=getSharedPreferences("amap_privacy",MODE_PRIVATE)
        if(!privacy.getBoolean("agreed",false)){
            android.app.AlertDialog.Builder(this).setTitle("家庭位置判断")
                .setMessage("为判断你是否在家，应用会在启动或绑定家庭模型时调用高德定位 SDK 获取一次经纬度、精度和地址。定位结果只保存在手机的家庭模型中，不上传到本项目后端。高德将按其隐私政策处理定位所需信息。")
                .setPositiveButton("同意并继续"){_,_->privacy.edit().putBoolean("agreed",true).apply();continueGeoOperation()}
                .setNegativeButton("暂不同意"){_,_->failGeoOperation("未同意定位服务，已跳过家庭自动判断")}.setCancelable(false).show()
        }else continueGeoOperation()
    }
    private fun beginNewHomeCollection(){
        pendingParentModelId=null
        localizationParentModelId=null
        pendingNewHomeFix=null
        activeModelId=null
        requestGeoOperation(GeoOperation.BIND_NEW_MODEL)
    }
    private fun continueGeoOperation(){
        val fine=checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
        val coarse=checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED
        if(!fine&&!coarse)locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))
        else runPendingGeoOperation()
    }
    private fun runPendingGeoOperation(){
        if(pendingGeoOperation==null)return
        message="正在获取一次手机位置…"
        AmapHomeLocator.locateOnce(this){result->runOnUiThread{
            if(isFinishing)return@runOnUiThread
            result.onSuccess(::handleGeoFix).onFailure{failGeoOperation("定位失败：${it.message?:it.javaClass.simpleName}")}
        }}
    }
    private fun handleGeoFix(fix:GeoFix){
        val operation=pendingGeoOperation?:return;pendingGeoOperation=null
        val parent=primaryHomeModel()
        when(operation){
            GeoOperation.BIND_NEW_MODEL->{pendingNewHomeFix=fix;scanMode=ScanMode.HOME;wantsScan=true;userRequestedInstall=true;message="已记录家庭位置，定位精度约${fix.accuracyMeters.toInt()}米；开始采集新家庭模型";checkCapability(true)}
            GeoOperation.BIND_EXISTING_MODEL->{if(parent==null){message="没有可绑定的家庭模型";return};modelLibrary.saveGeoAnchor(parent.id,fix.toAnchor());message="已将当前位置绑定到${parent.name}，精度约${fix.accuracyMeters.toInt()}米";say(message,SpeechPriority.ANSWER,"home-anchor-saved")}
            GeoOperation.STARTUP_HOME_CHECK,GeoOperation.MANUAL_HOME_CHECK->{
                if(parent==null){pendingGeoOperation=operation;failGeoOperation("没有可用家庭模型");return}
                val anchor=modelLibrary.loadGeoAnchor(parent.id)
                if(anchor==null){pendingGeoOperation=operation;failGeoOperation("${parent.name}还没有家庭位置，请先点击“将当前位置设为家庭位置”");return}
                val decision=HomeGeoPresence.evaluate(anchor,fix)
                if(!decision.atHome){val reason=decision.description;if(operation==GeoOperation.STARTUP_HOME_CHECK){scanMode=ScanMode.OUTDOOR;wantsScan=true;message="$reason，进入户外即时模式";checkCapability(true)}else{supervisedNavigationTest=false;supervisedRouteDisclaimerSpoken=false;pendingDestinationPrompt=false;message=reason;say(message,SpeechPriority.STATUS,"geo-away")};return}
                verifyBackendThenLocalize(parent,decision.description)
            }
        }
    }
    private fun verifyBackendThenLocalize(parent:HomeModelEntry,geoDescription:String){
        locatingHome=true;localizationAttempt++;val attempt=localizationAttempt;locatingSessionId=null;message="$geoDescription，已确认在家；正在连接家庭电脑"
        Thread({val evidence=if(homeBackendHost.isBlank())cn.visualassistant.app.home.HomePresenceEvidence(false,false) else HomePresenceVerifier.verify(this,homeBackendHost,homeBackendPort);runOnUiThread{
            if(!locatingHome||attempt!=localizationAttempt)return@runOnUiThread
            if(evidence.backendReachable)startHomePoseLocalization(parent,"$geoDescription，${evidence.description}")
                    else{locatingHome=false;supervisedNavigationTest=false;pendingDestinationPrompt=false;message="$geoDescription，已确认在家，但家庭电脑后端未连接，暂时无法判断具体房间";say(message,SpeechPriority.STATUS,"home-backend-offline")}
        }},"home-backend-check").start()
    }
    private fun failGeoOperation(reason:String){
        val operation=pendingGeoOperation;pendingGeoOperation=null;locatingHome=false;pendingNewHomeFix=null
        if(operation==GeoOperation.MANUAL_HOME_CHECK){supervisedNavigationTest=false;supervisedRouteDisclaimerSpoken=false;pendingDestinationPrompt=false}
        if(operation==GeoOperation.STARTUP_HOME_CHECK){scanMode=ScanMode.OUTDOOR;wantsScan=true;message="$reason，进入户外即时模式";checkCapability(true)}else{message=reason;say(message,SpeechPriority.STATUS,"geo-failed")}
    }
    private fun primaryHomeModel()=modelEntries.filter{it.parentModelId==null&&it.hasScanModel}.maxWithOrNull(compareBy<HomeModelEntry>{modelLibrary.loadPlaces(it.id).size}.thenBy{it.createdAt})
    private fun GeoFix.toAnchor()=HomeGeoAnchor(latitude,longitude,accuracyMeters,address,timeMs)

    /**
     * Look for the computer on the phone's own subnet.
     *
     * The usual setup is a phone hotspot with the computer joining it. The phone hands the
     * computer its address by DHCP, so a restart can move it and an address typed in once
     * stops working. Scanning the subnet for a machine that listens on the backend ports
     * survives that, and needs nothing on the computer side.
     */
    private fun discoverBackend(manual: Boolean) {
        if (discoveryRunning) return
        val now = SystemClock.elapsedRealtime()
        if (!manual && now - lastDiscoveryAt < DISCOVERY_COOLDOWN_MS) return
        discoveryRunning = true
        lastDiscoveryAt = now
        discoveryStatus = "正在搜索电脑…"
        android.util.Log.i("BackendDiscovery", "searching from ${BackendDiscovery.localIpv4Addresses()} for the computer")
        Thread({
            val candidates = BackendDiscovery.localSubnetHosts()
            val found = BackendDiscovery.findBackend(
                candidates,
                listOf(onlineBackendPort, homeBackendPort),
                PROBE_TIMEOUT_MS
            ) { host, port ->
                try {
                    java.net.Socket().use { it.connect(java.net.InetSocketAddress(host, port), PROBE_TIMEOUT_MS); true }
                } catch (e: Exception) { false }
            }
            android.util.Log.i("BackendDiscovery", "search finished: ${found ?: "nothing answered"}")
            runOnUiThread {
                discoveryRunning = false
                if (found != null) {
                    discoveryStatus = "已找到电脑 $found"
                    applyBackendHost(found)
                } else {
                    discoveryStatus = "没找到电脑：它可能连着别的网络，或后端没启动；点上面的地址可手填"
                }
            }
        }, "backend-discovery").start()
    }

    /** Try again later, but not on every failed connection attempt. */
    private fun scheduleBackendDiscovery() {
        if (discoveryRunning) return
        if (SystemClock.elapsedRealtime() - lastDiscoveryAt < DISCOVERY_COOLDOWN_MS) return
        discoverBackend(manual = false)
    }

    /** Point both channels at a new computer and remember it for the next launch. */
    private fun applyBackendHost(host: String) {
        if (host.isBlank() || host == backendAddress) return
        android.util.Log.i("BackendDiscovery", "switching the computer address to $host")
        backendAddress = host
        homeBackendHost = host
        getSharedPreferences("online_3d_connection", MODE_PRIVATE).edit().putString("host", host).apply()
        online3dClient?.retarget(host, onlineBackendPort)
        color3dClient?.retarget(host, homeBackendPort)
        message = "电脑地址已更新为 $host"
    }

    /** Let the user type the address when the automatic search cannot find it. */
    private fun promptBackendAddress() {
        val input = android.widget.EditText(this)
        input.setText(backendAddress)
        input.hint = "例如 192.168.43.7"
        android.app.AlertDialog.Builder(this)
            .setTitle("电脑地址")
            .setMessage("电脑连到手机热点后地址会变。可以直接填写，也可以点“重新搜索电脑”自动查找。")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val value = input.text.toString().trim()
                if (value.isNotEmpty()) applyBackendHost(value)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun ask() {        microphoneAction=MicrophoneAction.QUESTION
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO); return
        }
        cancelSpeech(); openMicrophone()
    }

    /**
     * Destination navigation over the persisted home map.
     *
     * Every instruction goes out through `say` at NAVIGATION priority, which the arbiter treats as
     * non-preempting, so a hazard at EMERGENCY still cuts in. That ordering is the hard boundary
     * between "a remembered route" and "what the sensors can see right now".
     *
     * Returns false when the utterance was not a navigation command, so ordinary questions and the
     * place-marking dialogue keep working exactly as before.
     */
    private fun handleHomeNavigationCommand(text:String):Boolean{
        val now=SystemClock.elapsedRealtime()
        val trimmed=text.trim()
        if(trimmed.isEmpty())return false

        if(trimmed.contains("停止导航")||trimmed.contains("取消导航")||trimmed.contains("结束导航")){
            if(navSession.activeRequest==null)return false
            val state=navSession.cancel("已停止导航")
            homeNavStatus="导航已停止"
            reportNavigation(state)
            return true
        }

        val startPrefix=listOf("我现在在","我站在","我在","从").firstOrNull{trimmed.contains(it)}
        if(startPrefix!=null){
            val map=homeMapForNavigation() ?: run{ say(homeNavStatus,SpeechPriority.ANSWER,"home-nav-error"); return true }
            val spoken=trimmed.substringAfter(startPrefix).substringBefore("，").substringBefore(",")
                .substringBefore("。").substringBefore("要去").substringBefore("我想去").trim()
            // The recogniser repeats words when unsure, exactly as it did with the destination.
            val place=matchBareDestination(spoken,map) ?: spoken
            if(place.isEmpty())return false
            val pose=currentLocalizedMapPose()
            if(pose==null&&navSession.activeRequest==null){
                // No destination yet and no pose: fall back to asking for the destination only,
                // because "我在X" cannot start a route on its own.
                say("先说要去哪里",SpeechPriority.ANSWER,"home-nav-error");return true
            }
            navPromptRetries=0
            val state=if(navSession.activeRequest==null){
                // "我在卧室，要去卫生间" — destination and start in one breath.
                val destination=extractDestination(trimmed) ?: matchBareDestination(trimmed,map) ?: run{
                    say("请说要去哪里，例如：我在卧室，要去卫生间",SpeechPriority.ANSWER,"home-nav-error");return true
                }
                val requested=navSession.requestDestination(map,destination,now)
                if(requested !is HomeNavigationSession.State.AwaitingStart){
                    reportNavigation(requested); return true
                }
                homeNavStatus="目标：$destination"
                // A spoken room name is not a pose. Only the verified persistent-map pose may
                // start a production route.
                if(pose==null)navSession.confirmStartAtAnchor(map,place,now)
                else navSession.startFromLocalizedPose(map,destination,pose.x,pose.z,
                    pose.forwardX,pose.forwardZ,now,maxRouteMeters=supervisedRouteLimitMeters())
            } else if(pose==null){
                navSession.confirmStartAtAnchor(map,place,now)
            } else {
                val request=navSession.activeRequest
                if(request==null)navSession.confirmStartAtAnchor(map,place,now)
                else navSession.startFromLocalizedPose(map,request.placeName,pose.x,pose.z,
                    pose.forwardX,pose.forwardZ,now,maxRouteMeters=supervisedRouteLimitMeters())
            }
            reportNavigation(state)
            return true
        }

        val map=homeMapForNavigation() ?: run{ say(homeNavStatus,SpeechPriority.ANSWER,"home-nav-error"); return true }
        // A bare place name is only a destination while the session is still collecting one. Once
        // it is waiting for the starting point, the same words mean "I am over here" — treating
        // them as a new destination reset the request on every repetition, so the app asked
        // "你要去哪里？" again and again no matter how clearly the user answered.
        if(navSession.activeRequest!=null){
            val place=matchBareDestination(trimmed,map) ?: return true
            navPromptRetries=0
            val pose=currentLocalizedMapPose()
            val request=navSession.activeRequest
            val state=if(pose==null)navSession.confirmStartAtAnchor(map,place,now)
            else if(request==null)navSession.confirmStartAtAnchor(map,place,now)
            else navSession.startFromLocalizedPose(map,request.placeName,pose.x,pose.z,
                pose.forwardX,pose.forwardZ,now,maxRouteMeters=supervisedRouteLimitMeters())
            reportNavigation(state)
            return true
        }

        val destination=extractDestination(trimmed) ?: matchBareDestination(trimmed,map) ?: run{
            // A destination the user clearly believes exists. Say what does exist rather than
            // falling through to the generic question answerer, which is what made a perfectly
            // good "卫生间" get an unrelated reply.
            val names=map.anchorList.map{it.name}
            say("没有听清要去哪里。已标记的地点有：${names.joinToString("、")}。请说其中之一",SpeechPriority.ANSWER,"home-nav-error")
            return true
        }
        navPromptRetries=0
        homeNavStatus="目标：$destination"
        val localized=currentLocalizedMapPose()
        if(localized!=null){
            reportNavigation(navSession.startFromLocalizedPose(map,destination,localized.x,localized.z,
                localized.forwardX,localized.forwardZ,now,maxRouteMeters=supervisedRouteLimitMeters()))
        }else{
            val pending=navSession.requestDestination(map,destination,now)
            reportNavigation(if(pending is HomeNavigationSession.State.AwaitingStart)
                HomeNavigationSession.State.NeedsAlignment(pending.request,"家庭精确定位已经失效，请先点家庭自动定位，定位成功后再导航")
            else pending)
        }
        return true
    }

    /**
     * Opens the microphone for a destination request, listing the places that actually exist.
     *
     * A map with cells but no anchors still cannot answer "take me to the bathroom". Saying that
     * up front is far kinder than letting somebody speak a destination that can never resolve.
     */
    private fun startDestinationVoicePrompt(){
        if(locatingHome){
            homeNavStatus="家庭实时定位正在电脑端处理中，请等待定位结果后再选择目的地"
            say(homeNavStatus,SpeechPriority.STATUS,"home-localization-pending")
            return
        }
        val map=homeMapForNavigation() ?: run{ say(homeNavStatus,SpeechPriority.ANSWER,"home-nav-error"); return }
        val names=map.anchorList.map{it.name}
        if(names.isEmpty()){
            say("这个家地图有地形但没有标记地点，导不了航。需要重新采集一次家庭路线，走到卧室、卫生间时点“标记当前位置”并说出名字",
                SpeechPriority.ANSWER,"home-nav-error");return
        }
        cancelSpeech()
        navPromptRetries=0
        navListeningFor=NavPrompt.DESTINATION
        // The place list is deliberately not spoken up front. Five names take five seconds, and
        // the user naturally starts answering during them, so their own reply is buried under the
        // prompt. The list is only read out after a turn that clearly matched nothing.
        fun askDestination(){
            say("要去哪里？",SpeechPriority.ANSWER,"home-nav-prompt"){
                openMicrophone("请说要去哪里")
            }
        }
        if(supervisedNavigationTest&&!supervisedRouteDisclaimerSpoken){
            supervisedRouteDisclaimerSpoken=true
            say("这是陪同实验候选路线，不是安全认证。请陪同者先确认前方安全并全程陪同。",SpeechPriority.ANSWER,"home-nav-test-warning"){
                askDestination()
            }
        }else{
            askDestination()
        }
    }

    /**
     * Re-asks whatever the last navigation turn was waiting for.
     *
     * A blind user who says nothing and gets silence back has no way to know whether the app is
     * listening, broken, or simply finished. After a failed turn the app must speak the expected
     * sentence again and reopen the microphone, at most [NAV_RETRY_LIMIT] times so a persistently
     * dead microphone cannot trap the user in an endless loop.
     */
    private fun retryNavigationPrompt(){
        if(voice.isListening)return
        val kind=navListeningFor ?: return
        if(navPromptRetries>=NAV_RETRY_LIMIT){
            navPromptRetries=0
            say("连续几次都没有听清。请检查手机麦克风是否被其他应用占用，或者直接点“去某个标记地点”重来",SpeechPriority.ANSWER,"home-nav-error")
            return
        }
        navPromptRetries++
        // One short line, then listen. The previous wording repeated the whole question and the
        // example, which took longer to speak than the user needed to answer and made the app feel
        // like it was nagging rather than waiting.
        when(kind){
            NavPrompt.DESTINATION->{
                say("再说一次，要去哪？",SpeechPriority.ANSWER,"home-nav-retry"){
                    openMicrophone("请说要去哪里")
                }
            }
            NavPrompt.START->{
                say("你现在在哪里？",SpeechPriority.ANSWER,"home-nav-retry"){
                    openMicrophone("请说你现在在哪里")
                }
            }
            NavPrompt.REALIGN->{
                say("再说一次你在哪里",SpeechPriority.ANSWER,"home-nav-retry"){
                    openMicrophone("请再说一次位置")
                }
            }
        }
    }

    /** Pulls "我要去X" / "导航到X" / "带我去X" / "去X" out of an utterance. */
    private fun extractDestination(text:String):String?{
        listOf("我要去","我想去","导航到","带我去","带我到","去").forEach{marker->
            val index=text.indexOf(marker)
            if(index>=0){
                val rest=text.substring(index+marker.length).trim('，','。',' ','去')
                val name=normalizePlaceName(rest)
                if(!name.isNullOrEmpty())return name
            }
        }
        return null
    }

    /**
     * Accepts a bare place name as a destination.
     *
     * People do not always say "我要去卫生间" — after the prompt says "请说要去哪里", the natural
     * reply is just "卫生间". A small recogniser also also tends to repeat a word
     * ("卫生间卫生间卫生间") when it is unsure, and taking that literally would match nothing.
     * Matching the whole utterance against the known names also covers the case where the
     * "我要去" marker itself was misheard.
     */
    private fun matchBareDestination(text:String,map:HomeMap):String?{
        val compact=text.filterNot{it.isWhitespace()||it=='，'||it=='。'||it=='、'}
        if(compact.isEmpty())return null
        for(length in 2..4){
            if(compact.length==length*3){
                val head=compact.take(length)
                if((0 until compact.length).all{compact[it]==head[it%length]})return head
            }
        }
        val names=map.anchorList.map{it.name}.filter{it.isNotBlank()}
        names.firstOrNull{compact==it||(it.length>=2&&compact.contains(it))}?.let{return it}
        // A small recogniser confuses homophones constantly — 二寝室 / 一寝室, 卫生间 / 卫生进.
        // Matching on the first character alone is only offered when it is unambiguous, because
        // guessing between two real rooms is exactly the failure a blind user cannot detect.
        val heads=names.map{it to it.first()}.distinctBy{it.second}
        if(heads.size==names.size){
            val hit=heads.firstOrNull{compact.contains(it.second)}
            if(hit!=null){
                android.util.Log.i("SpatialVoice","fuzzy matched \"$compact\" to ${hit.first}")
                return hit.first
            }
        }
        return null
    }

    private fun reportNavigation(state:HomeNavigationSession.State){
        val spoken=navSession.speakable()
        when(state){
            is HomeNavigationSession.State.Idle->{navListeningFor=null;navPromptRetries=0}
            is HomeNavigationSession.State.AwaitingStart->{
                navListeningFor=NavPrompt.START
                say(spoken?:state.question,SpeechPriority.ANSWER,"home-nav"){
                    openMicrophone("请说你现在在哪里")
                }
            }
            is HomeNavigationSession.State.NeedsAlignment->{
                navListeningFor=NavPrompt.REALIGN
                say(spoken?:state.reason,SpeechPriority.ANSWER,"home-nav"){
                    openMicrophone("请再说一次位置")
                }
            }
            is HomeNavigationSession.State.Navigating->{
                navListeningFor=null;navPromptRetries=0
                spoken?.let{ say(it,SpeechPriority.NAVIGATION,"home-nav") }
                homeNavStatus=if(supervisedNavigationTest)
                    "陪同实验候选路线：前往${state.request.placeName}；必须由陪同者确认前方安全"
                else "导航中：${state.request.placeName}"
            }
            is HomeNavigationSession.State.Arriving->{
                navListeningFor=null;navPromptRetries=0
                spoken?.let{ say(it,SpeechPriority.NAVIGATION,"home-nav") }
                homeNavStatus="已到标记点附近，请确认"
            }
            is HomeNavigationSession.State.Done->{
                navListeningFor=null;navPromptRetries=0
                say(state.outcome,SpeechPriority.ANSWER,"home-nav")
                homeNavStatus=state.outcome
            }
        }
    }

    /**
     * Current position as (x, z, forward) from the live ARCore pose.
     *
     * Null when tracking is unusable or the phone is pointed too far off horizontal: feeding a
     * stale or meaningless pose into a route is worse than asking the user to wait two seconds.
     * The forward component is nullable because without it no turn may be spoken.
     */
    private fun currentNavPose():Triple<Float,Float,Pair<Float?,Float?>>?{
        // A route needs a tracked *pose*, not depth. The gate is therefore the last TRACKING frame,
        // never `snapshot.usable` (which is about depth) and never `lastFrameAtMs` (which ticks
        // while ARCore is still hunting). Conflating those two is what made the app answer
        // "还拿不到当前位置" forever whenever the phone pointed at a plain white wall.
        if(renderer.lastTrackedAtMs<=0L)return null
        if(SystemClock.elapsedRealtime()-renderer.lastTrackedAtMs>3_000L)return null
        val camera=renderer.currentCamera() ?: return null
        val forward=camera.forward
        return Triple(camera.origin.x,camera.origin.z,
            if(forward!=null) forward.x to forward.z else null to null)
    }

    /** Current ARCore pose transformed into the persistent model selected by visual relocalization. */
    private fun currentLocalizedMapPose():MapPose?{
        val transform=localizedHomeTransform ?: return null
        if(renderer.lastTrackedAtMs<=0L||SystemClock.elapsedRealtime()-renderer.lastTrackedAtMs>3_000L)return null
        val camera=renderer.currentCamera() ?: return null
        val forward=camera.fullForward ?: return null
        return runCatching{transform.horizontalPose(camera.origin.x,camera.origin.y,camera.origin.z,
            forward.x,forward.y,forward.z)}.getOrNull()
    }

    private fun beginPlaceLabel(){
        microphoneAction=MicrophoneAction.PLACE
        android.util.Log.i("SpatialPlace","mark tapped scanning=$scanning mode=$scanMode model=$activeModelId")
        if(!scanning||scanMode!=ScanMode.HOME){
            // Every silent refusal here used to look identical to a broken button, so each branch
            // says which precondition failed and speaks it.
            val why=if(!scanning)"扫描还没开始，请先点采集家庭路线" else "当前是户外扫描模式，标记地点只能在采集家庭路线时用"
            message=why;say(why,SpeechPriority.ANSWER,"place-precondition");return
        }
        // The place anchor must come from the same local ARCore frame as the grid cells, not from the
        // remote backend echo. If the backend is offline or lags, remoteCameraPose freezes and every
        // subsequent place lands on the same coordinates.
        val camera=renderer.currentCamera()
        val forward=camera?.fullForward
        if(activeModelId==null||camera==null||forward==null){
            // The two failures need different instructions: a missing model id is a state bug, while
            // a missing camera means ARCore has no pose yet and the user just has to hold steady.
            val why=when{
                activeModelId==null->"这个扫描还没有关联模型，无法标记地点。请结束扫描后重新开始采集家庭路线"
                camera==null->"还定位不到当前位置，暂时不能标记。请把手机对准门框或墙角缓慢移动，等定位建立"
                else->"手机定位刚刚丢失，暂时不能标记。请停稳手机对准有纹理的地方再试"
            }
            android.util.Log.i("SpatialPlace","mark refused: model=${activeModelId} camera=${camera!=null} forward=${forward!=null}")
            message=why;say(why,SpeechPriority.ANSWER,"place-precondition");return
        }
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            android.util.Log.i("SpatialPlace","mark needs microphone permission")
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO);return
        }
        val pose=RemotePose3(camera.origin.x,camera.origin.y,camera.origin.z,forward.x,forward.y,forward.z)
        android.util.Log.i("SpatialPlace","mark armed at x=${"%.2f".format(camera.origin.x)} z=${"%.2f".format(camera.origin.z)}")
        pendingPlacePose=pose;pendingPlaceName="";placeDialogStage=PlaceDialogStage.NAME
        cancelSpeech();say("请说这里是什么地方，例如：这是卧室",SpeechPriority.ANSWER,"place-prompt"){
            openMicrophone("请说这里是什么地方")
        }
    }

    private fun handleVoiceResult(text:String){
        // Recognition problems are invisible without this line: a dropped turn produces no callback
        // at all, so the logcat is the only place that shows what the recogniser actually heard.
        android.util.Log.i("SpatialVoice", "recognised=\"$text\" navState=${navSession.state::class.simpleName} listening=${voice.isListening} peak=${voice.lastPeakPercent}%")
        if(voiceTurnPolluted){
            // The user answered while our own prompt was still audible. Their words are in there
            // somewhere but mixed with ours, so the safe move is to say so and ask once more rather
            // than act on a half-overlapped guess.
            voiceTurnPolluted=false
            android.util.Log.i("SpatialVoice","discarding a turn that overlapped a prompt")
            say("刚才我的播报还没说完，请等我说完再说一次",SpeechPriority.ANSWER,"home-nav-retry")
            return
        }
        if(handleHomeNavigationCommand(text))return
        when(placeDialogStage){
            PlaceDialogStage.NONE->say(answerQuestion(text),SpeechPriority.ANSWER,"answer")
            PlaceDialogStage.NAME->{
                val name=normalizePlaceName(text)
                if(name==null){placeDialogStage=PlaceDialogStage.NONE;pendingPlacePose=null;say("没有听清有效的房间名称，请重新点击标记当前位置，例如说这是卧室",SpeechPriority.ANSWER,"place-error");return}
                pendingPlaceName=name;placeDialogStage=PlaceDialogStage.CONFIRM
                feedback.cancelDialog();say("将这里标记为${name}，是否正确？",SpeechPriority.ANSWER,"place-confirm"){
                    openMicrophone("请回答正确或不正确")
                }
            }
            PlaceDialogStage.CONFIRM->{
                // Vosk frequently transcribes a plain "对" as a homophone, and a small recogniser
                // makes that worse. Requiring one exact word meant a correctly answered question was
                // thrown away as "not understood", which is the single most frustrating way for this
                // dialog to fail. Acceptance therefore also covers the common homophones, and
                // anything short that is not an explicit refusal counts as agreement.
                val rejected=listOf("不对","不正确","错误","取消","不是","不","别").any{text==it||text.contains(it)}
                val agreed=listOf("正确","确认","是","对","可以","没問題","没问题","嗯","好","行","ok").any{text.contains(it)}
                val shortAffirmative=text.length<=3&&text.isNotBlank()
                val accepted=!rejected&&(agreed||shortAffirmative)
                val modelId=activeModelId;val pose=pendingPlacePose;val name=pendingPlaceName
                placeDialogStage=PlaceDialogStage.NONE;pendingPlaceName="";pendingPlacePose=null;feedback.cancelDialog()
                android.util.Log.i("SpatialPlace","confirm heard=\"$text\" accepted=$accepted rejected=$rejected agreed=$agreed model=$modelId pose=${pose!=null}")
                if(accepted&&modelId!=null&&pose!=null){
                    modelLibrary.addPlace(modelId,name,pose);libraryPlaces=modelLibrary.loadPlaces(modelId)
                    android.util.Log.i("SpatialPlace","saved \"$name\"; places now ${libraryPlaces.size}")
                    say("已由你确认，将这里标记为${name}",SpeechPriority.ANSWER,"place-saved")
                }else if(rejected)say("已取消${name}标记，请重新标记",SpeechPriority.ANSWER,"place-cancelled")
                else if(modelId==null)say("这次扫描没有关联模型，标记没有保存。请结束扫描后重新开始采集家庭路线",SpeechPriority.ANSWER,"place-error")
                else if(pose==null)say("当前位置已经丢失，这次标记没有保存。请重新点击标记当前位置",SpeechPriority.ANSWER,"place-error")
                else say("没有听清确认结果，本次没有保存，请重新标记",SpeechPriority.ANSWER,"place-unclear")
            }
        }
    }

    /**
     * Feeds detector results into the semantic memory used for questions. Hazard speech is
     * deliberately not emitted here: only the fused spatial snapshot may combine a name with
     * direction and distance, so asynchronous YOLO and depth results cannot be cross-paired.
     */
    private fun onDetections(detections: List<Detection>) {
        labels = detections.filter { it.confidence >= .5f }.map { it.label }.distinct().take(4)
        if (!scanning) return
        viewModel.onDetections(detections, 0)
    }

    /**
     * Names and short-term memory come from the detector; bearings and distances come from
     * the depth map. "刚才/之前" must go to the memory layer, because the depth map only
     * holds what is confirmed right now and would answer "还没有找到" for past objects.
     */
    private fun answerQuestion(question: String): String {
        val now = SystemClock.elapsedRealtime()
        val spatial = snapshot.takeIf { it.usable && now - it.time <= 1500 }?.describe(question, now)
        val semantic = viewModel.answer(question)
        val asksHistory = listOf("刚才","之前","上次","最早").any { it in question }
        return when {
            asksHistory && semantic != null -> semantic
            spatial != null && !spatial.contains("还没有") && !spatial.contains("暂不能") -> spatial
            semantic != null -> semantic
            spatial != null -> spatial
            else -> "暂时没有看清周围，请继续缓慢扫描。"
        }
    }
    private fun say(text: String, priority: SpeechPriority=SpeechPriority.STATUS, key: String="scan-status",onDone:()->Unit={}) {
        message=text
        android.util.Log.i("SpatialSpeech","speaking: $text")
        if(priority==SpeechPriority.ANSWER) feedback.speakAnswer(text,onDone)
        else feedback.speak(text,priority,key,if(priority==SpeechPriority.NAVIGATION) 4_000 else 10_000,onDone)
    }
    private fun cancelSpeech() { voice.cancel(); feedback.cancelDialog() }

    /**
     * Opens the microphone once the loudspeaker has actually gone quiet.
     *
     * Every "say then listen" step used to open the mic straight from the TTS completion callback.
     * That callback fires while the phone is still audibly speaking and the speaker cone is still
     * ringing, so the recogniser transcribed the prompt — "请说要去哪里" came back as the user's
     * own request, and a real answer spoken over the tail of a prompt was mangled beyond use.
     * Waiting for the silence is the fix; the recogniser itself was never the problem.
     */
    private fun openMicrophone(prompt:String="请说"){
        feedback.beginDialog()
        handler.postDelayed({ voice.start(prompt) },SPEAKER_SETTLE_MS)
    }

    /**
     * Folds this session's confirmed cells into the model's home map and writes it out.
     *
     * Must run before `renderer.clearMap(true)`, which throws the session grid away, and only
     * after the GL surface has paused so the grid is not being written while it is read. A session
     * that confirmed nothing is not a failure — it just leaves the existing map alone.
     */
    private fun persistHomeMap(modelId: String): Boolean {
        val observed = runCatching { renderer.exportGrid() }.getOrNull() ?: return false
        if (observed.isEmpty()) return false
        val map = modelLibrary.loadHomeMap(modelId) ?: HomeMap()
        map.mergeObserved(observed)
        // loadHomeMap already synced anchors from places.json; after merging the newest cells we
        // just write the map back. A place marked seconds before the scan ended is therefore saved.
        if (!modelLibrary.saveHomeMap(modelId, map)) return false
        modelLibrary.setHomeMapCells(modelId, map.cellCount)
        homeNavStatus = "家地图已更新，共 ${map.cellCount} 格、${map.anchorList.size} 个标记地点"
        return true
    }

    /**
     * One tick of destination navigation.
     *
     * The live obstacle list is handed in on every tick so a moved chair is reported instead of
     * the remembered route being followed into it. Returns null when there is nothing new to say;
     * the session does its own repeat suppression.
     */
    private fun advanceHomeNavigation(now:Long):String?{
        if(navSession.state !is HomeNavigationSession.State.Navigating)return null
        val map=homeNavMap ?: return null
        val pose=currentLocalizedMapPose()
        if(pose==null){
            navSession.cancel("家庭定位已丢失，请停下并重新定位")
            homeNavStatus="家庭定位已丢失，导航停止"
            return "家庭定位已丢失，请立即停下，重新进行家庭自动定位"
        }
        if(now-snapshot.time>3_000L){
            navSession.cancel("实时障碍数据已过期，请停下")
            homeNavStatus="实时障碍数据中断，导航停止"
            return "实时障碍数据已中断，请立即停下"
        }
        val transform=localizedHomeTransform ?: return null
        val sessionY=renderer.currentCamera()?.origin?.y ?: return null
        val liveOccupied=snapshot.grid.mapNotNull { cell ->
            if(cell.state!=TraversabilityState.OCCUPIED||cell.inflated)null
            else cell.worldX?.let{x->cell.worldZ?.let{z->
                val p=transform.point(x,sessionY,z);p[0] to p[2]
            }}
        }
        val instruction=navSession.update(map,pose.x,pose.z,now,liveOccupied)
        if(navSession.state is HomeNavigationSession.State.Done){
            homeNavStatus="导航已停止：${navSession.speakable().orEmpty()}"
        }
        return instruction
    }

    /**
     * Loads the map a destination request would run against, or null with a spoken reason.
     *
     * Models that actually have a map are tried first. Taking the newest entry blindly would
     * report "no home map yet" while a perfectly good older one sits there, which reads to the
     * user exactly like data loss.
     */
    private fun homeMapForNavigation(): HomeMap? {
        if(locatingHome){
            homeNavStatus="家庭实时定位正在电脑端处理中，请等待定位结果后再选择目的地"
            return null
        }
        val localizedId=localizedHomeTransform?.modelId
        if(localizedId!=null){
            val entry=modelEntries.firstOrNull{it.id==localizedId}
            val readiness=HomeNavigationReadinessGate.evaluate(
                hasMapCells=entry?.hasHomeMap==true,
                backendValidatedNavigationGraph=entry?.navigationReady==true,
                // Routes are still produced by the legacy 2D scan grid, not the backend 3D graph.
                verifiedGraphConsumedByPlanner=false,
                supervisedTestMode=supervisedNavigationTest,
                liveLocalizedPose=currentLocalizedMapPose()!=null
            )
            if(!readiness.ready){homeNavStatus=readiness.message;return null}
            homeNavStatus=readiness.message
            val map=modelLibrary.loadHomeMap(localizedId)
            if(map!=null&&map.cellCount>0){homeNavMap=map;return map}
            homeNavStatus="导航图已验证，但家庭地图文件读取失败，请重新同步模型"
            return null
        }
        val preferred=listOfNotNull(activeModelId,selectedLibraryEntry?.id)
        val candidates=preferred+modelEntries.filter{it.hasHomeMap&&it.navigationReady}.map{it.id}
        for(id in candidates.distinct()){
            val entry=modelEntries.firstOrNull{it.id==id} ?: continue
            val readiness=HomeNavigationReadinessGate.evaluate(
                entry.hasHomeMap,entry.navigationReady,verifiedGraphConsumedByPlanner=false,
                supervisedTestMode=supervisedNavigationTest,liveLocalizedPose=currentLocalizedMapPose()!=null)
            if(!readiness.ready)continue
            homeNavStatus=readiness.message
            val map=modelLibrary.loadHomeMap(id)
            if(map!=null&&map.cellCount>0){ homeNavMap=map; return map }
        }
        homeNavStatus=when{
            modelEntries.none{it.hasHomeMap}->"还没有家庭地图。请先采集家庭路线并标记房间和地点"
            supervisedNavigationTest&&localizedHomeTransform==null->"家庭实时定位尚未完成，请先完成家庭自动定位"
            modelEntries.none{it.hasHomeMap&&it.navigationReady}->HomeNavigationReadinessGate.evaluate(true,false).message
            else->"已验证的家庭导航图文件读取失败，请重新同步模型"
        }
        return null
    }

    private fun stopScan(userInitiated:Boolean=false) {
        // The user was mid-sentence: a capture ending on its own must not take the microphone with
        // it. Only an explicit "结束扫描" tap is allowed to cut the current voice turn short.
        val userTapped = userInitiated
        if(supervisedNavigationTest){
            navSession.cancel("陪同实验导航已停止")
            supervisedNavigationTest=false;supervisedRouteDisclaimerSpoken=false;pendingDestinationPrompt=false
        }
        localizedHomeTransform=null
        // A localization observation is intentionally not a library model, but
        // the color backend still needs an explicit finish packet to start PnP.
        val localizationSession=if(activeModelId==null&&locatingHome)locatingSessionId else null
        if(localizationSession!=null&&!localizationSubmitted)color3dClient?.finishHome(localizationSession,emptyList())
        cancelHomeLocalization()
        val finishedModelId=activeModelId
        activeModelId?.let{id->
            val places=modelLibrary.loadPlaces(id).map{ConfirmedPlacePacket(it.id,it.name,it.createdAt,it.x,it.y,it.z,it.forwardX,it.forwardY,it.forwardZ)}
            val session=id.toULongOrNull()?.toLong()
            if(session!=null){online3dClient?.finishHome(session,places);color3dClient?.finishHome(session,places)}
        }
        // The session grid is thrown away by clearMap below, so the home map has to be written
        // first — and only after the GL surface is paused, because the grid is written per frame
        // and reading it mid-frame would race the render thread.
        activeModelId=null
        scanning=false
        if(glRunning) { surface.onPause(); glRunning=false }
        val savedMap = if(finishedModelId!=null) persistHomeMap(finishedModelId) else false
        finishedModelId?.let{id->
            modelLibrary.updateStatus(id, if(savedMap) "扫描地图已保存，导航图待验证" else "扫描已提交，等待电脑处理")
            modelEntries=modelLibrary.load()
        }
        renderer.session?.let { session ->
            session.pause(); renderer.clearMap(true); session.close()
        }
        renderer.session=null; snapshot=SpatialSnapshot(status="扫描已结束"); lastUsable=null
        guiding=false; lastGuidanceText=""; lastGuidanceAt=0
        prompts.reset(); silenceUntil=0; pendingNotice=null
        remoteVoxelMap.clear();remoteMapStatus="";remoteVoxels=emptyList();remoteCameraPose=null;remoteTrajectory=emptyList();remoteQualityStatus="";remoteMesh=null;remoteColoredMesh=null;lastQualityNoticeAt=0
        placeDialogStage=PlaceDialogStage.NONE;pendingPlaceName="";pendingPlacePose=null
        if(userTapped)cancelSpeech()
        scanMode=ScanMode.OUTDOOR
        if(userTapped){navSession.cancel("已结束扫描，导航停止");homeNavMap=null}
    }

    /**
     * Aborts the "am I in which room" auto-localisation flow.
     *
     * It re-arms a capture every few seconds and narrates each attempt, so any navigation dialogue
     * running alongside it is drowned out and the microphone is taken away mid-sentence. Since the
     * half-automatic map deliberately replaces that flow with the user saying "我在卧室", the two
     * must never overlap.
     */
    private fun cancelHomeLocalization(){
        if(!locatingHome&&localizationParentModelId==null)return
        locatingHome=false;localizationAttempt++;locatingSessionId=null
        localizationParentModelId=null;localizationObservationStarted=false;localizationValidDepthFrames=0
    }
    override fun onResume() {
        super.onResume(); foreground=true
        handler.removeCallbacks(voiceTick); handler.post(voiceTick)
        val pausedSession=renderer.session
        if(pausedForLifecycle&&pausedSession!=null){
            runCatching{
                pausedSession.resume();surface.onResume();glRunning=true;scanning=true;pausedForLifecycle=false
                watchdog.reset();renderer.markFrameClock();message="已继续原家庭扫描，之前的数据仍然保留"
                activeModelId?.let{modelLibrary.updateStatus(it,"正在继续扫描")};modelEntries=modelLibrary.load()
            }.onFailure{
                message="原扫描无法直接恢复，已保存电脑端检查点；请结束本段后重新采集"
                pendingNotice=message
            }
        }else if(startupPresencePending){startupPresencePending=false;routeStartupByPresence()} else checkCapability(wantsScan)
    }
    override fun onPause() {
        foreground=false; capabilityAttempt++; handler.removeCallbacks(voiceTick)
        if(scanning&&renderer.session!=null){
            pausedForLifecycle=true;scanning=false
            if(glRunning){surface.onPause();glRunning=false}
            runCatching{renderer.session?.pause()}
            activeModelId?.let{modelLibrary.updateStatus(it,"扫描已暂停，返回应用可继续")};modelEntries=modelLibrary.load()
        }
        super.onPause()
    }
    override fun onDestroy() {
        // Being killed is not a reason to lose the walk. The grid is still intact here, and the
        // render thread is about to go away with the activity, so this is the last chance to save.
        activeModelId?.let{id->
            val saved=runCatching{persistHomeMap(id)}.getOrDefault(false)
            modelLibrary.updateStatus(id, if(saved) "扫描地图已保存，导航图待验证" else "扫描中断，电脑端检查点已保留")
        }
        navSession.reset(); homeNavMap=null
        handler.removeCallbacksAndMessages(null)
        online3dClient?.close(); online3dClient=null;color3dClient?.close();color3dClient=null
        renderer.close(); voice.close(); feedback.close(); super.onDestroy()
    }
}

private enum class PlaceDialogStage{NONE,NAME,CONFIRM}
private enum class NavPrompt{DESTINATION,START,REALIGN}
private enum class MicrophoneAction{QUESTION,PLACE}
private enum class GeoOperation{STARTUP_HOME_CHECK,MANUAL_HOME_CHECK,BIND_NEW_MODEL,BIND_EXISTING_MODEL}

internal fun normalizePlaceName(text:String):String?{
    val name=text.removePrefix("这里是").removePrefix("这是").removePrefix("这个是").removeSuffix("房间").trim('，','。',' ')
    val invalid=setOf("过去","这里","那里","这个","那个","地方","不知道","随便","没听清")
    return name.takeIf{it.isNotBlank()&&it.length<=20&&it !in invalid}
}

@Composable
private fun ScanText(text: String, modifier: Modifier = Modifier) {
    Text(text,modifier=modifier,color=Color.White,
        style=MaterialTheme.typography.bodyLarge.copy(shadow=Shadow(Color.Black,Offset(0f,2f),6f)))
}

@Composable
private fun FloatingActionText(text:String){
    Text(text,color=Color(0xFFB7F4D2),style=MaterialTheme.typography.titleMedium.copy(
        shadow=Shadow(Color.Black,Offset(0f,2f),7f)))
}

@Composable
private fun HomeModelLibraryPage(
    entries:List<HomeModelEntry>,selected:HomeModelEntry?,scanMesh:RemoteColoredMesh?,optimizedMesh:RemoteColoredMesh?,cognition:SavedCognition?,places:List<HomePlace>,
    onBack:()->Unit,onOpen:(HomeModelEntry)->Unit,onDelete:(HomeModelEntry)->Unit,onResumeScan:(HomeModelEntry)->Unit,
    onRenamePlace:(HomePlace,String)->Unit,onDeletePlace:(HomePlace)->Unit
){
    var deleteTarget by remember{mutableStateOf<HomeModelEntry?>(null)}
    var deletePlaceTarget by remember{mutableStateOf<HomePlace?>(null)}
    var renamePlaceTarget by remember{mutableStateOf<HomePlace?>(null)}
    var renameText by remember{mutableStateOf("")}
    var showCognition by remember(selected?.id){mutableStateOf(selected?.let{!it.hasScanModel&&it.hasCognitionModel}==true)}
    var showOptimized by remember(selected?.id){mutableStateOf(selected?.hasOptimizedModel==true)}
    deleteTarget?.let{entry->AlertDialog(onDismissRequest={deleteTarget=null},title={Text("删除家庭模型？")},text={Text("将从手机删除“${entry.name}”的真实扫描、碰撞认知和模型条目。电脑上的原始模型不会删除。")},confirmButton={Button(onClick={onDelete(entry);deleteTarget=null}){Text("删除")}},dismissButton={TextButton(onClick={deleteTarget=null}){Text("取消")}})}
    deletePlaceTarget?.let{place->AlertDialog(onDismissRequest={deletePlaceTarget=null},title={Text("删除地点标记？")},text={Text("删除用户确认的地点“${place.name}”。")},confirmButton={Button(onClick={onDeletePlace(place);deletePlaceTarget=null}){Text("删除")}},dismissButton={TextButton(onClick={deletePlaceTarget=null}){Text("取消")}})}
    renamePlaceTarget?.let{place->AlertDialog(onDismissRequest={renamePlaceTarget=null},title={Text("重命名地点")},text={OutlinedTextField(value=renameText,onValueChange={renameText=it.take(20)},label={Text("用户确认的新名称")})},confirmButton={Button(onClick={onRenamePlace(place,renameText);renamePlaceTarget=null},enabled=renameText.isNotBlank()){Text("保存")}},dismissButton={TextButton(onClick={renamePlaceTarget=null}){Text("取消")}})}
    Column(Modifier.fillMaxSize().background(Color(0xFF101418)).safeDrawingPadding().padding(16.dp)){
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
            TextButton(onClick=onBack){Text("返回")}
            Text(if(selected==null)"家庭模型库" else selected.name,style=MaterialTheme.typography.headlineSmall,modifier=Modifier.weight(1f))
            if(selected!=null)TextButton(onClick={deleteTarget=selected}){Text("删除",color=Color(0xFFFF8A80))}
        }
        if(selected==null){
            Text("已保存 ${entries.size} 个家庭模型",color=Color(0xFFB7F4D2),modifier=Modifier.padding(vertical=8.dp))
            if(entries.isEmpty())Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Text("还没有家庭模型\n点击“采集家庭路线”创建第一个模型")}
            else LazyColumn(Modifier.fillMaxSize()){
                items(entries,key={it.id}){entry->
                    Card(Modifier.fillMaxWidth().padding(vertical=7.dp).clickable(enabled=entry.hasScanModel||entry.hasCognitionModel){onOpen(entry)}){
                        Column(Modifier.padding(16.dp)){
                            Text(entry.name,style=MaterialTheme.typography.titleLarge);Text(entry.displayTime)
                            Text("状态：${entry.status}");Text("模型编号：${entry.id.takeLast(8)}")
                            // A map with no cells cannot be navigated, whatever the mesh says. Saying
                            // "ready" here would send a blind user to a destination that does not exist.
                            if(entry.hasHomeMap) Text("家地图已就绪 · 覆盖 ${entry.homeMapCells} 格",color=Color(0xFFB7F4D2))
                            else Text("家地图尚未建立 · 需要先走一遍路线",color=Color(0xFFFFD166))
                            if(entry.parentModelId!=null) Text("补扫分段 ${entry.segmentIndex} · 等待电脑空间对齐",color=Color(0xFFFFD166))
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                                AssistChip(onClick={onOpen(entry)},enabled=entry.hasScanModel,label={Text(if(entry.hasScanModel)"真实扫描 ✓" else "真实扫描处理中")})
                                AssistChip(onClick={onOpen(entry)},enabled=entry.hasCognitionModel,label={Text(if(entry.hasCognitionModel)"占据证据 ✓" else "占据证据处理中")})
                                AssistChip(onClick={onOpen(entry)},label={Text(if(entry.hasOptimizedModel)"电脑优化 ✓" else "同步电脑优化")})
                            }
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){
                                if(entry.parentModelId==null) TextButton(onClick={onResumeScan(entry)}){Text("继续补扫")}
                                TextButton(onClick={deleteTarget=entry}){Text("删除模型",color=Color(0xFFFF8A80))}
                            }
                        }
                    }
                }
            }
        }else{
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center){
                FilterChip(selected=!showCognition&&!showOptimized,onClick={showCognition=false;showOptimized=false},enabled=scanMesh!=null,label={Text("原始扫描")})
                Spacer(Modifier.width(12.dp))
                FilterChip(selected=showOptimized,onClick={showOptimized=true;showCognition=false},enabled=optimizedMesh!=null,label={Text("电脑优化")})
                Spacer(Modifier.width(12.dp))
                FilterChip(selected=showCognition,onClick={showCognition=true;showOptimized=false},enabled=cognition!=null,label={Text("空间占据")})
            }
            Spacer(Modifier.height(8.dp))
            when{
                showOptimized&&optimizedMesh!=null->Column(Modifier.fillMaxWidth().weight(1f),horizontalAlignment=Alignment.CenterHorizontally){
                    AndroidView(factory={ColoredMeshSurface(it)},update={viewer->viewer.setMesh(optimizedMesh);viewer.setPlaces(places)},modifier=Modifier.fillMaxWidth().weight(1f))
                    Text(selected.optimizedStatus,color=if(selected.navigationReady)Color(0xFFB7F4D2) else Color(0xFFFFD166),modifier=Modifier.padding(8.dp))
                }
                showCognition&&cognition!=null->Column(Modifier.fillMaxWidth().weight(1f),horizontalAlignment=Alignment.CenterHorizontally){
                    Text("版本 ${cognition.revision} · ${cognition.voxels.size} 个空间单元")
                    RemoteVoxelPreview(cognition.voxels,cognition.voxelSize,null,emptyList(),Modifier.fillMaxWidth().weight(1f))
                }
                scanMesh!=null->Column(Modifier.fillMaxWidth().weight(1f),horizontalAlignment=Alignment.CenterHorizontally){
                    AndroidView(factory={ColoredMeshSurface(it)},update={viewer->viewer.setMesh(scanMesh);viewer.setPlaces(places)},modifier=Modifier.fillMaxWidth().weight(1f));Text("实体表面 · 彩色标点为用户地点 · 单指旋转 · 双指缩放",modifier=Modifier.padding(8.dp))
                }
                else->Box(Modifier.fillMaxWidth().weight(1f),contentAlignment=Alignment.Center){Text("这个历史条目没有保存可查看的模型")}
            }
            Text("用户确认的地点（${places.size}）",style=MaterialTheme.typography.titleMedium,color=Color(0xFFB7F4D2))
            val navHint=if(places.isEmpty())"尚未标记。家庭扫描时点击“标记当前位置”，按语音提示说出地点名称，之后就能说“我要去卫生间”。"
                else "已标记 $places.size 个地点。开始扫描后可直接说“我要去${places.first().name}”。"
            if(places.isEmpty())Text(navHint,modifier=Modifier.padding(8.dp))
            else Column(Modifier.fillMaxWidth().heightIn(max=150.dp).verticalScroll(rememberScrollState())){
                Text(navHint,modifier=Modifier.padding(vertical=4.dp))
                places.forEachIndexed{index,place->Row(Modifier.fillMaxWidth().padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically){
                    Text("● ${index+1}  ${place.name}",color=placeMarkerColor(index),modifier=Modifier.weight(1f));TextButton(onClick={renamePlaceTarget=place;renameText=place.name}){Text("改名")};TextButton(onClick={deletePlaceTarget=place}){Text("删除",color=Color(0xFFFF8A80))}
                }}
            }
        }
    }
}

private fun placeMarkerColor(index:Int)=listOf(Color(0xFFFF4038),Color(0xFF33BFFF),Color(0xFFFFBF1A),Color(0xFF8CFF59),Color(0xFFCC66FF))[index%5]

@Composable
private fun MapPreview(snapshot: SpatialSnapshot) {
    Column(Modifier.padding(8.dp), horizontalAlignment=Alignment.CenterHorizontally) {
        ScanText(if(snapshot.floorHeight == null) "正在确认地面 · 灰色均为未知" else "绿色候选可走 · 红色障碍 · 灰色未知")
        Canvas(Modifier.size(184.dp).semantics {
            contentDescription=if(snapshot.floorHeight == null) "尚未形成可通行区域，请继续缓慢扫描脚前方。"
            else "可通行区域俯视图，绿色为候选可走区域，红色为障碍及安全余量，灰色为未知。"
        }) {
            val center=Offset(size.width/2,size.height/2)
            val scale=size.width/12
            drawRect(Color(0xCC30363B))
            drawLine(Color(0xFF59636A),Offset(center.x,0f),Offset(center.x,size.height))
            drawLine(Color(0xFF59636A),Offset(0f,center.y),Offset(size.width,center.y))
            val cellPixels=TraversabilityGrid.CELL_SIZE*scale+0.75f
            snapshot.grid.filter { it.state==TraversabilityState.FREE }.forEach {
                drawRect(Color(0xFF55D98B),Offset(center.x+it.relativeX*scale-cellPixels/2,
                    center.y+it.relativeZ*scale-cellPixels/2),androidx.compose.ui.geometry.Size(cellPixels,cellPixels))
            }
            snapshot.grid.filter { it.state==TraversabilityState.OCCUPIED }.forEach {
                drawRect(if(it.inflated) Color(0xFFE57474) else Color(0xFFFF4D4D),
                    Offset(center.x+it.relativeX*scale-cellPixels/2,center.y+it.relativeZ*scale-cellPixels/2),
                    androidx.compose.ui.geometry.Size(cellPixels,cellPixels))
            }
            drawCircle(Color.White,4f,center)
            drawLine(Color.White,center,center-Offset(0f,12f),3f)
        }
    }
}

/** Compact live map layered over the camera. Its resize/move handle owns its gestures, so the
 * underlying 3-D viewer never interprets a map gesture as a camera orbit or pinch. */
@Composable
private fun FloatingMiniMap(snapshot:SpatialSnapshot,modifier:Modifier=Modifier){
    var mapSizeDp by remember{mutableFloatStateOf(156f)}
    var mapOffset by remember{mutableStateOf<IntOffset?>(null)}
    val density=LocalDensity.current
    BoxWithConstraints(modifier){
        val sizePx=with(density){mapSizeDp.dp.roundToPx()}
        val maxX=(constraints.maxWidth-sizePx).coerceAtLeast(0)
        val maxY=(constraints.maxHeight-sizePx).coerceAtLeast(0)
        val rightInset=with(density){12.dp.roundToPx()}
        val topInset=with(density){142.dp.roundToPx()}
        val x=(mapOffset?.x?: (maxX-rightInset).coerceAtLeast(0)).coerceIn(0,maxX)
        val y=(mapOffset?.y?: topInset.coerceAtMost(maxY)).coerceIn(0,maxY)
        Surface(
            modifier=Modifier.offset{IntOffset(x,y)}.size(mapSizeDp.dp)
                .pointerInput(Unit){
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed=false).consume()
                        var pressed=true
                        while(pressed){
                            val event=awaitPointerEvent()
                            pressed=false
                            event.changes.forEach{change->
                                if(change.pressed)pressed=true
                                change.consume()
                            }
                        }
                    }
                },
            shape=RoundedCornerShape(18.dp),
            color=Color(0x99101519),
            border=BorderStroke(1.dp,Color.White.copy(alpha=.18f)),
            shadowElevation=2.dp
        ){
            Box(Modifier.fillMaxSize()){
                Column(Modifier.fillMaxSize().padding(bottom=7.dp)){
                    Row(Modifier.fillMaxWidth().height(28.dp).padding(horizontal=10.dp),verticalAlignment=Alignment.CenterVertically){
                        Text("脚下",color=Color(0xFF24313A),style=MaterialTheme.typography.labelMedium,modifier=Modifier.weight(1f),maxLines=1)
                        Text(if(snapshot.floorHeight==null)"未知" else "实时",color=Color(0xFF46545D),style=MaterialTheme.typography.labelSmall,maxLines=1)
                    }
                    Canvas(Modifier.fillMaxWidth().weight(1f).padding(horizontal=5.dp).semantics{
                        contentDescription=if(snapshot.floorHeight==null)"脚下地图仍未知区域较多" else "脚下局部地图，绿色为候选可走，红色为障碍，灰色为未知"
                    }){
                        val center=Offset(size.width/2f,size.height/2f)
                        val scale=minOf(size.width,size.height)/10f
                        drawLine(Color(0xFF647078),Offset(center.x,0f),Offset(center.x,size.height),1f)
                        drawLine(Color(0xFF647078),Offset(0f,center.y),Offset(size.width,center.y),1f)
                        val cell=(TraversabilityGrid.CELL_SIZE*scale+1f).coerceAtLeast(2f)
                        snapshot.grid.filter{it.state==TraversabilityState.FREE}.forEach{cellData->
                            drawRect(Color(0xFF55D98B),Offset(center.x+cellData.relativeX*scale-cell/2,center.y+cellData.relativeZ*scale-cell/2),androidx.compose.ui.geometry.Size(cell,cell))
                        }
                        snapshot.grid.filter{it.state==TraversabilityState.OCCUPIED}.forEach{cellData->
                            drawRect(if(cellData.inflated)Color(0xFFE57474)else Color(0xFFFF4D4D),Offset(center.x+cellData.relativeX*scale-cell/2,center.y+cellData.relativeZ*scale-cell/2),androidx.compose.ui.geometry.Size(cell,cell))
                        }
                        drawCircle(Color.White,4f,center)
                        drawLine(Color.White,center,center-Offset(0f,12f),3f)
                    }
                }
                Box(
                    Modifier.align(Alignment.BottomEnd).size(42.dp)
                        // Keep the detector alive while the window is resized or moved. Keying it
                        // to maxX/maxY restarted the pointer coroutine on every size update, which
                        // dropped motion events and made the handle feel sticky.
                        .pointerInput(Unit){
                            awaitEachGesture {
                                val down=awaitFirstDown(requireUnconsumed=false)
                                val origin=down.position
                                var previous=origin
                                var mode=0 // 0 waiting, 1 resize, 2 move after long press
                                while(true){
                                    val event=awaitPointerEvent()
                                    val change=event.changes.firstOrNull{it.id==down.id}?:break
                                    val delta=change.position-previous
                                    if(mode==0){
                                        val held=change.uptimeMillis-down.uptimeMillis
                                        if(held>=viewConfiguration.longPressTimeoutMillis)mode=2
                                        else if((change.position-origin).getDistance()>=viewConfiguration.touchSlop)mode=1
                                    }
                                    if(delta!=Offset.Zero){
                                        if(mode==1)mapSizeDp=(mapSizeDp+delta.x/density.density).coerceIn(148f,270f)
                                        if(mode==2){
                                            val current=mapOffset?:IntOffset(x,y)
                                            mapOffset=IntOffset((current.x+delta.x.toInt()).coerceIn(0,maxX),(current.y+delta.y.toInt()).coerceIn(0,maxY))
                                        }
                                        if(mode!=0)change.consume()
                                    }
                                    previous=change.position
                                    if(!change.pressed)break
                                }
                            }
                        }
                        .background(Color(0xB0101519),RoundedCornerShape(topStart=12.dp,bottomEnd=18.dp)),
                    contentAlignment=Alignment.Center
                ){
                    Text("↘",color=Color(0xFFB7F4D2),style=MaterialTheme.typography.titleMedium,
                        modifier=Modifier.semantics{contentDescription="拖动缩放地图；长按后拖动可移动悬浮窗"})
                }
            }
        }
    }
}

/** Small independent 3-D preview in the upper-left corner; its handle moves/resizes the window
 * while touches on the model itself remain available for orbit and pinch gestures. */
@Composable
private fun FloatingModelPreview(mesh:RemoteColoredMesh){
    var windowSize by remember{mutableFloatStateOf(156f)}
    var windowOffset by remember{mutableStateOf<IntOffset?>(null)}
    val density=LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()){
        val sizePx=with(density){windowSize.dp.roundToPx()}
        val maxX=(constraints.maxWidth-sizePx).coerceAtLeast(0)
        val maxY=(constraints.maxHeight-sizePx).coerceAtLeast(0)
        val left=with(density){12.dp.roundToPx()}
        val top=with(density){142.dp.roundToPx()}
        val x=(windowOffset?.x?:left).coerceIn(0,maxX)
        val y=(windowOffset?.y?:top.coerceAtMost(maxY)).coerceIn(0,maxY)
        Surface(Modifier.offset{IntOffset(x,y)}.size(windowSize.dp),shape=RoundedCornerShape(16.dp),
            color=Color(0x99101519),border=BorderStroke(1.dp,Color.White.copy(alpha=.18f)),shadowElevation=2.dp){
            Box(Modifier.fillMaxSize()){
                AndroidView(factory={ColoredMeshSurface(it)},update={it.setMesh(mesh)},modifier=Modifier.fillMaxSize().padding(bottom=8.dp))
                Box(Modifier.align(Alignment.TopCenter).background(Color(0x99101519),RoundedCornerShape(bottomStart=8.dp,bottomEnd=8.dp)).padding(horizontal=8.dp,vertical=3.dp)){
                    Text("家庭3D",color=Color.White,style=MaterialTheme.typography.labelSmall)
                }
                Box(Modifier.align(Alignment.BottomEnd).size(38.dp)
                    .background(Color(0xB0101519),RoundedCornerShape(topStart=10.dp,bottomEnd=16.dp))
                    // The handle must keep one gesture coroutine for the entire drag. The old
                    // geometry keys changed as the window resized, cancelling the gesture.
                    .pointerInput(Unit){
                        awaitEachGesture {
                            val down=awaitFirstDown(requireUnconsumed=false);val origin=down.position;var previous=origin;var mode=0
                            while(true){
                                val event=awaitPointerEvent();val change=event.changes.firstOrNull{it.id==down.id}?:break
                                val delta=change.position-previous
                                if(mode==0){
                                    val held=change.uptimeMillis-down.uptimeMillis
                                    if(held>=viewConfiguration.longPressTimeoutMillis)mode=2
                                    else if((change.position-origin).getDistance()>=viewConfiguration.touchSlop)mode=1
                                }
                                if(delta!=Offset.Zero){
                                    if(mode==1)windowSize=(windowSize+delta.x/density.density).coerceIn(124f,230f)
                                    if(mode==2){val current=windowOffset?:IntOffset(x,y);windowOffset=IntOffset((current.x+delta.x.toInt()).coerceIn(0,maxX),(current.y+delta.y.toInt()).coerceIn(0,maxY))}
                                    if(mode!=0)change.consume()
                                }
                                previous=change.position;if(!change.pressed)break
                            }
                        }
                    },contentAlignment=Alignment.Center){
                    Text("↘",color=Color(0xFFB7F4D2),style=MaterialTheme.typography.labelMedium,
                        modifier=Modifier.semantics{contentDescription="拖动缩放三维模型窗；长按后拖动可移动窗口"})
                }
            }
        }
    }
}

@Composable
private fun ModelWaitingPreview(status:String){
    Column(Modifier.padding(12.dp),horizontalAlignment=Alignment.CenterHorizontally){
        ScanText(status)
        ScanText(if("未连接" in status||"未配置" in status)"请连接电脑后重新进入家庭扫描" else "请缓慢扫描墙角、地面和门洞；通常 8 个可靠帧后首次显示")
    }
}

@Composable
private fun OpenGlColoredMeshPreview(mesh:RemoteColoredMesh){
    Column(Modifier.padding(8.dp),horizontalAlignment=Alignment.CenterHorizontally){
        ScanText("家庭彩色三维模型 · ${mesh.triangles.size} 个三角面")
        AndroidView(factory={ColoredMeshSurface(it)},update={it.setMesh(mesh)},modifier=Modifier.size(320.dp))
        ScanText("真实向上 · 默认轻微俯视 · 单指旋转 · 双指缩放")
    }
}

/** Real camera colour is retained on the reconstructed surface so a family member can match it to the room. */
@Composable
private fun RemoteColoredMeshPreview(triangles:List<RemoteColoredTriangle>,camera:RemotePose3?,trajectory:List<RemotePose3>){
    var yaw by remember{mutableFloatStateOf(.65f)};var pitch by remember{mutableFloatStateOf(-.45f)};var zoom by remember{mutableFloatStateOf(1f)}
    val shown=remember(triangles){val stride=maxOf(1,triangles.size/10_000);triangles.filterIndexed{i,_->i%stride==0}.take(10_000)}
    val points=remember(shown){shown.flatMap{listOf(it.a,it.b,it.c)}}
    val centerX=((points.minOfOrNull{it.x}?:0f)+(points.maxOfOrNull{it.x}?:0f))/2f
    val centerY=((points.minOfOrNull{it.y}?:0f)+(points.maxOfOrNull{it.y}?:0f))/2f
    val centerZ=((points.minOfOrNull{it.z}?:0f)+(points.maxOfOrNull{it.z}?:0f))/2f
    Column(Modifier.padding(8.dp),horizontalAlignment=Alignment.CenterHorizontally){
        ScanText("家庭彩色三维模型 · ${triangles.size} 个三角面 · 拖动旋转 · 双指缩放")
        Canvas(Modifier.size(300.dp).pointerInput(Unit){detectTransformGestures{_,pan,gestureZoom,_->yaw+=pan.x*.01f;pitch=(pitch+pan.y*.006f).coerceIn(-1.3f,1.3f);zoom=(zoom*gestureZoom).coerceIn(.4f,4f)}}.semantics{contentDescription="电脑生成的家庭彩色三维模型，共${triangles.size}个三角面。"}){
            drawRect(Color(0xEE171B1E));val yc=cos(yaw);val ys=sin(yaw);val pc=cos(pitch);val ps=sin(pitch)
            fun raw(p:cn.visualassistant.app.online3d.RemotePoint3):Triple<Float,Float,Float>{val x=p.x-centerX;val y=p.y-centerY;val z=p.z-centerZ;val rx=x*yc-z*ys;val rz=x*ys+z*yc;return Triple(rx,-(y*pc-rz*ps),rz)}
            val projected=shown.map{t->val pa=raw(t.a);val pb=raw(t.b);val pv=raw(t.c);Triple((pa.third+pb.third+pv.third)/3f,listOf(pa,pb,pv),t)}.sortedByDescending{it.first}
            val extent=projected.flatMap{it.second}.maxOfOrNull{maxOf(kotlin.math.abs(it.first),kotlin.math.abs(it.second))}?.coerceAtLeast(.5f)?:1f
            val scale=minOf(size.width,size.height)*.46f/extent*zoom;val origin=Offset(size.width/2,size.height/2)
            projected.forEach{(_,vertices,t)->
                val light=(.55f+.45f*kotlin.math.abs(t.normal.x*.2f+t.normal.y*.8f+t.normal.z*.35f)).coerceIn(.35f,1f)
                val red=(t.ar+t.br+t.cr)/3f/255f;val green=(t.ag+t.bg+t.cg)/3f/255f;val blue=(t.ab+t.bb+t.cb)/3f/255f
                val color=Color((red*light).coerceIn(0f,1f),(green*light).coerceIn(0f,1f),(blue*light).coerceIn(0f,1f),1f)
                val p=vertices.map{origin+Offset(it.first,it.second)*scale};val path=Path().apply{moveTo(p[0].x,p[0].y);lineTo(p[1].x,p[1].y);lineTo(p[2].x,p[2].y);close()};drawPath(path,color)
            }
            fun projectPose(p:RemotePose3):Offset{val r=raw(cn.visualassistant.app.online3d.RemotePoint3(p.x,p.y,p.z));return origin+Offset(r.first,r.second)*scale}
            trajectory.zipWithNext().forEach{(a,b)->drawLine(Color(0xFF55BFFF),projectPose(a),projectPose(b),2f)};camera?.let{drawCircle(Color.White,5f,projectPose(it))}
        }
        ScanText("表面颜色来自相机画面 · 蓝线为扫描轨迹")
    }
}

/** Shaded TSDF triangle mesh intended for a family member, rather than diagnostic points. */
@Composable
private fun RemoteMeshPreview(triangles:List<RemoteTriangle>,camera:RemotePose3?,trajectory:List<RemotePose3>){
    var yaw by remember{mutableFloatStateOf(.65f)};var pitch by remember{mutableFloatStateOf(-.45f)};var zoom by remember{mutableFloatStateOf(1f)}
    val shown=remember(triangles){val stride=maxOf(1,triangles.size/8_000);triangles.filterIndexed{i,_->i%stride==0}.take(8_000)}
    val points=remember(shown){shown.flatMap{listOf(it.a,it.b,it.c)}}
    val centerX=((points.minOfOrNull{it.x}?:0f)+(points.maxOfOrNull{it.x}?:0f))/2f
    val centerY=((points.minOfOrNull{it.y}?:0f)+(points.maxOfOrNull{it.y}?:0f))/2f
    val centerZ=((points.minOfOrNull{it.z}?:0f)+(points.maxOfOrNull{it.z}?:0f))/2f
    Column(Modifier.padding(8.dp),horizontalAlignment=Alignment.CenterHorizontally){
        ScanText("家庭三维表面 · ${triangles.size} 个三角面 · 拖动旋转 · 双指缩放")
        Canvas(Modifier.size(260.dp).pointerInput(Unit){detectTransformGestures{_,pan,gestureZoom,_->yaw+=pan.x*.01f;pitch=(pitch+pan.y*.006f).coerceIn(-1.3f,1.3f);zoom=(zoom*gestureZoom).coerceIn(.45f,4f)}}.semantics{contentDescription="电脑生成的家庭连续三维表面，共${triangles.size}个三角面。"}){
            drawRect(Color(0xDD20272C));val yc=cos(yaw);val ys=sin(yaw);val pc=cos(pitch);val ps=sin(pitch)
            fun raw(p:cn.visualassistant.app.online3d.RemotePoint3):Triple<Float,Float,Float>{val x=p.x-centerX;val y=p.y-centerY;val z=p.z-centerZ;val rx=x*yc-z*ys;val rz=x*ys+z*yc;return Triple(rx,-(y*pc-rz*ps),rz)}
            val projected=shown.map{t->val pa=raw(t.a);val pb=raw(t.b);val pcv=raw(t.c);Triple((pa.third+pb.third+pcv.third)/3f,listOf(pa,pb,pcv),t)}.sortedByDescending{it.first}
            val extent=projected.flatMap{it.second}.maxOfOrNull{maxOf(kotlin.math.abs(it.first),kotlin.math.abs(it.second))}?.coerceAtLeast(.5f)?:1f
            val scale=minOf(size.width,size.height)*.44f/extent*zoom;val origin=Offset(size.width/2,size.height/2)
            projected.forEach{(_,vertices,t)->
                val normalLight=(kotlin.math.abs(t.normal.x*.25f+t.normal.y*.75f+t.normal.z*.45f)).coerceIn(.15f,1f)
                val base=if(kotlin.math.abs(t.normal.y)>.72f)Color(0xFFB7B9B4) else Color(0xFF8FA9B8)
                val color=Color(base.red*normalLight,base.green*normalLight,base.blue*normalLight,.96f)
                val p=vertices.map{origin+Offset(it.first,it.second)*scale};val path=Path().apply{moveTo(p[0].x,p[0].y);lineTo(p[1].x,p[1].y);lineTo(p[2].x,p[2].y);close()};drawPath(path,color)
            }
            fun projectPose(p:RemotePose3):Offset{val r=raw(cn.visualassistant.app.online3d.RemotePoint3(p.x,p.y,p.z));return origin+Offset(r.first,r.second)*scale}
            trajectory.zipWithNext().forEach{(a,b)->drawLine(Color(0xFF55BFFF),projectPose(a),projectPose(b),2f)}
            camera?.let{drawCircle(Color.White,5f,projectPose(it))}
        }
        ScanText("灰色地面 · 蓝灰墙面/物体 · 蓝线扫描轨迹")
    }
}

/**
 * Diagnostic voxel preview retained for engineering inspection only.  The renderer deliberately draws a bounded sample:
 * navigation keeps the complete synchronized map, while Compose never has to paint tens of
 * thousands of points on the UI thread.  Drag rotates the model and pinch changes its scale.
 */
@Composable
private fun RemoteVoxelPreview(voxels: List<RemoteVoxel>, voxelSize: Float, camera:RemotePose3?, trajectory:List<RemotePose3>, modifier:Modifier=Modifier.size(220.dp)) {
    var yaw by remember { mutableFloatStateOf(.65f) }
    var pitch by remember { mutableFloatStateOf(-.45f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    val shown = remember(voxels) {
        val stride = maxOf(1, voxels.size / 4_000)
        voxels.filterIndexed { index, _ -> index % stride == 0 }.take(4_000)
    }
    val centerX = remember(shown) {
        ((shown.minOfOrNull { it.x } ?: 0) + (shown.maxOfOrNull { it.x } ?: 0)) / 2f
    }
    val centerY = remember(shown) {
        ((shown.minOfOrNull { it.y } ?: 0) + (shown.maxOfOrNull { it.y } ?: 0)) / 2f
    }
    val centerZ = remember(shown) {
        ((shown.minOfOrNull { it.z } ?: 0) + (shown.maxOfOrNull { it.z } ?: 0)) / 2f
    }

    Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        ScanText("家庭三维模型 · 拖动旋转 · 双指缩放")
        Canvas(
            modifier
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, gestureZoom, _ ->
                        yaw += pan.x * .01f
                        pitch = (pitch + pan.y * .006f).coerceIn(-1.3f, 1.3f)
                        zoom = (zoom * gestureZoom).coerceIn(.45f, 4f)
                    }
                }
                .semantics {
                    contentDescription =
                        "家庭三维模型，共${voxels.size}个空间单元。红色是可靠障碍，绿色是可靠空闲，黄色是暂定，紫色是冲突。"
                }
        ) {
            drawRect(Color(0xCC30363B))
            val yawCos = cos(yaw)
            val yawSin = sin(yaw)
            val pitchCos = cos(pitch)
            val pitchSin = sin(pitch)
            val projected = shown.map { voxel ->
                val x = (voxel.x - centerX) * voxelSize
                val y = (voxel.y - centerY) * voxelSize
                val z = (voxel.z - centerZ) * voxelSize
                val rotatedX = x * yawCos - z * yawSin
                val rotatedZ = x * yawSin + z * yawCos
                val screenY = y * pitchCos - rotatedZ * pitchSin
                Triple(rotatedZ, Offset(rotatedX, -screenY), voxel)
            }.sortedByDescending { it.first }
            val furthest = projected.maxOfOrNull {
                maxOf(kotlin.math.abs(it.second.x), kotlin.math.abs(it.second.y))
            }?.coerceAtLeast(.5f) ?: 1f
            val scale = minOf(size.width, size.height) * .43f / furthest * zoom
            val origin = Offset(size.width / 2f, size.height / 2f)
            fun projectWorld(x:Float,y:Float,z:Float):Offset {
                val localX=x-centerX*voxelSize;val localY=y-centerY*voxelSize;val localZ=z-centerZ*voxelSize
                val rotatedX=localX*yawCos-localZ*yawSin
                val rotatedZ=localX*yawSin+localZ*yawCos
                return origin+Offset(rotatedX,-(localY*pitchCos-rotatedZ*pitchSin))*scale
            }
            fun depthWorld(x:Float,z:Float):Float {val localX=x-centerX*voxelSize;val localZ=z-centerZ*voxelSize;return localX*yawSin+localZ*yawCos}
            data class Face(val depth:Float,val points:List<Offset>,val color:Color)
            val faces=ArrayList<Face>()
            projected.forEach { (_, point, voxel) ->
                val color = when (voxel.state) {
                    RemoteVoxelState.RELIABLE_FREE -> Color(0xFF55D98B)
                    RemoteVoxelState.RELIABLE_OCCUPIED -> Color(0xFFFF5656)
                    RemoteVoxelState.TENTATIVE -> Color(0xFFFFCE5C)
                    RemoteVoxelState.CONFLICT -> Color(0xFFC879FF)
                }
                val alpha = (.35f + voxel.confidence * .65f).coerceIn(.35f, 1f)
                if(voxel.surfaceMask==0) drawCircle(color.copy(alpha = alpha), radius = 2.2f, center = origin + point * scale)
                else {
                    val x0=voxel.x*voxelSize;val x1=(voxel.x+1)*voxelSize
                    val y0=voxel.y*voxelSize;val y1=(voxel.y+1)*voxelSize
                    val z0=voxel.z*voxelSize;val z1=(voxel.z+1)*voxelSize
                    val corners=arrayOf(
                        arrayOf(floatArrayOf(x0,y0,z0),floatArrayOf(x0,y1,z0),floatArrayOf(x0,y1,z1),floatArrayOf(x0,y0,z1)),
                        arrayOf(floatArrayOf(x1,y0,z1),floatArrayOf(x1,y1,z1),floatArrayOf(x1,y1,z0),floatArrayOf(x1,y0,z0)),
                        arrayOf(floatArrayOf(x0,y0,z1),floatArrayOf(x1,y0,z1),floatArrayOf(x1,y0,z0),floatArrayOf(x0,y0,z0)),
                        arrayOf(floatArrayOf(x0,y1,z0),floatArrayOf(x1,y1,z0),floatArrayOf(x1,y1,z1),floatArrayOf(x0,y1,z1)),
                        arrayOf(floatArrayOf(x1,y0,z0),floatArrayOf(x1,y1,z0),floatArrayOf(x0,y1,z0),floatArrayOf(x0,y0,z0)),
                        arrayOf(floatArrayOf(x0,y0,z1),floatArrayOf(x0,y1,z1),floatArrayOf(x1,y1,z1),floatArrayOf(x1,y0,z1)),
                    )
                    for(faceIndex in 0..5) if(voxel.surfaceMask and (1 shl faceIndex)!=0){
                        val face=corners[faceIndex]
                        val points=face.map{projectWorld(it[0],it[1],it[2])}
                        val depth=face.map{depthWorld(it[0],it[2])}.average().toFloat()
                        faces+=Face(depth,points,color.copy(alpha=.82f))
                    }
                }
            }
            faces.sortedByDescending{it.depth}.forEach{face->val path=Path().apply{moveTo(face.points[0].x,face.points[0].y);face.points.drop(1).forEach{lineTo(it.x,it.y)};close()};drawPath(path,face.color)}
            if(trajectory.size>1) {
                trajectory.zipWithNext().forEach{(a,b)->drawLine(Color(0xFF75C9FF),projectWorld(a.x,a.y,a.z),projectWorld(b.x,b.y,b.z),2f)}
            }
            camera?.let { pose ->
                val phone=projectWorld(pose.x,pose.y,pose.z)
                val facing=projectWorld(pose.x+pose.forwardX*.35f,pose.y+pose.forwardY*.35f,pose.z+pose.forwardZ*.35f)
                drawCircle(Color.White,5f,phone);drawLine(Color.White,phone,facing,3f)
            }
        }
        ScanText("绿空闲 · 红障碍 · 黄暂定 · 紫冲突 · 蓝线扫描轨迹")
    }
}
