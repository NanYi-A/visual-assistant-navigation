package cn.visualassistant.app.spatial

import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES20.*
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.SystemClock
import com.google.ar.core.*
import com.google.ar.core.exceptions.NotYetAvailableException
import cn.visualassistant.app.inference.YoloDetector
import cn.visualassistant.app.core.Detection
import cn.visualassistant.app.online3d.Online3dFrame
import cn.visualassistant.app.online3d.Color3dFrame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.*

/** Session and anchors accessed only while the GL thread is running; inference owns its own model. */
class SpatialRenderer(
    context: Context,
    private val publish: (SpatialSnapshot) -> Unit,
    /**
     * Every YOLO result, whatever the depth says. The spatial map only adopts a label once
     * several depth points back it up; the semantic layer needs every frame instead, so the
     * fused screen can say what something is even before it can say how far away it is.
     */
    private val publishDetections: ((List<Detection>, Long) -> Unit)? = null,
    private val publishOnlineFrame: ((Online3dFrame) -> Unit)? = null,
    private val publishColorFrame: ((Color3dFrame) -> Unit)? = null,
) : GLSurfaceView.Renderer {
    @Volatile var scanMode: ScanMode = ScanMode.OUTDOOR
    @Volatile var session: Session? = null
    @Volatile var imageRotation: Int = 90
    /** Wall clock of the last frame delivered by ARCore; drives the capture watchdog. */
    @Volatile var lastFrameAtMs: Long = 0
        private set
    /**
     * Wall clock of the last frame whose tracking state was TRACKING.
     *
     * [lastFrameAtMs] advances even while ARCore is hunting, so it answers "is the camera running",
     * not "do we know where we are". Navigation needs the second question, and pointing a phone at a
     * blank wall produces plenty of frames with no pose at all.
     */
    @Volatile var lastTrackedAtMs: Long = 0
        private set
    private val app = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor()
    private val colorWorker = Executors.newSingleThreadExecutor()
    private var detector: YoloDetector? = null
    private val busy = AtomicBoolean(false)
    private val colorBusy = AtomicBoolean(false)
    private var texture = 0
    private var backgroundProgram = 0
    private var pointProgram = 0
    private var width = 1
    private var height = 1
    private var lastSample = 0L
    private var lastPublish = 0L
    private var frameErrors = 0
    private var firstFrameErrorAt = 0L
    private var trackingLostAt = 0L
    private val depthGate = DepthStreamGate()
    private val sessionGrid = SessionGridMemory()
    private val objectTracker = SpatialObjectTracker()
    private var lastDepthLog = 0L
    private var lastPose: Pose? = null
    private var lastPoseAt = 0L
    /**
     * Most recent camera transform, published for navigation outside the render thread.
     *
     * Volatile because the render thread writes it every frame while the activity's ticker reads it.
     */
    @Volatile private var latestCamera: CameraTransform? = null
    /** Latest pose, or null when no frame has produced one yet. */
    fun currentCamera(): CameraTransform? = latestCamera
    private var tracking = false
    private var everTracked = false
    /** Prevents a changed AR world origin from being fused into the existing backend TSDF. */
    private var captureSuspendedForPoseJump = false
    private var cachedWorld: List<WorldSample> = emptyList()
    private var pointBuffer: FloatBuffer? = null
    private val quad = buffer(floatArrayOf(-1f,-1f, 1f,-1f, -1f,1f, 1f,1f))
    private val uv = buffer(FloatArray(8))
    private data class Keyframe(val pose: Pose, val time: Long, val id: Long, val samples: List<DepthImageSample>,
        @Volatile var objects: List<CameraObjectObservation> = emptyList())
    private val keyframes = ArrayDeque<Keyframe>()
    private var onlineFrameId = 0L
    private var onlineSessionId = System.nanoTime()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val ids = IntArray(1)
        glGenTextures(1, ids, 0)
        texture = ids[0]
        glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        backgroundProgram = program("attribute vec2 p; attribute vec2 uv; varying vec2 t; void main(){gl_Position=vec4(p,0.,1.);t=uv;}",
            "#extension GL_OES_EGL_image_external : require\nprecision mediump float; uniform samplerExternalOES camera; varying vec2 t; void main(){gl_FragColor=texture2D(camera,t);}")
        pointProgram = program("attribute vec3 p; uniform mat4 vp; void main(){gl_Position=vp*vec4(p,1.);gl_PointSize=4.;}",
            "precision mediump float; void main(){gl_FragColor=vec4(0.4,1.,0.75,1.);}")
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) { width = w; height = h; glViewport(0,0,w,h) }

    override fun onDrawFrame(gl: GL10?) {
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        val active = session ?: return
        val now = SystemClock.elapsedRealtime()
        try {
            active.setCameraTextureName(texture)
            active.setDisplayGeometry(android.view.Surface.ROTATION_0, width, height)
            val frame = active.update()
            if (frame.timestamp == 0L) return
            lastFrameAtMs = now
            frameErrors = 0
            drawCamera(frame)
            val camera = frame.camera
            if(captureSuspendedForPoseJump){
                emit(SpatialSnapshot(now,status="空间坐标已跳变，请结束本次扫描后重新开始；已暂停写入以保护原模型",phase=ScanPhase.ERROR),now,true)
                return
            }
            if (camera.trackingState != TrackingState.TRACKING) {
                // A brief loss (hand wobble, motion blur) does not invalidate a
                // capture: the coordinate system survives and the walk continues.
                // Only a sustained loss ends it. Frames are not recorded while lost.
                if (!everTracked) {
                    tracking = false
                    emit(SpatialSnapshot(now, status = "正在建立定位，请原地缓慢平移手机", phase = ScanPhase.LOCATING), now)
                    return
                }
                if (trackingLostAt == 0L) trackingLostAt = now
                val lostFor=now-trackingLostAt
                tracking = false
                // Keep the previous pose, depth gate and keyframes during a short loss.
                // ARCore often recovers in the same world coordinates; clearing here caused
                // repeated depth warm-up and let a changed origin corrupt one backend TSDF.
                val status=if(lostFor<TRACKING_LOST_GRACE_MS) "定位短暂不稳定，请停稳手机" else "定位持续丢失，请回到有纹理区域缓慢恢复"
                emit(SpatialSnapshot(now,status=status,phase=ScanPhase.LOST),now)
                return
            }
            if (trackingLostAt != 0L) {
                // Recovery after a relocalization can silently jump the pose; compare
                // against the pre-loss pose before trusting the same coordinate system.
                trackingLostAt = 0L
                val old = lastPose
                if (old != null && poseJump(camera.pose, old) > .8f) {
                    captureSuspendedForPoseJump=true
                    emit(SpatialSnapshot(now,status="空间坐标发生跳变，已暂停写入以保护原模型；请结束后重新开始",phase=ScanPhase.ERROR),now,true)
                    return
                }
            }
            lastTrackedAtMs = now
            // Relocalization/large pose jumps invalidate this local map instead of moving old obstacles.
            val old = lastPose
            if (old != null && now - lastPoseAt < 1000 && poseJump(camera.pose, old) > .8f) {
                captureSuspendedForPoseJump=true
                emit(SpatialSnapshot(now,status="空间坐标发生跳变，已暂停写入以保护原模型；请结束后重新开始",phase=ScanPhase.ERROR),now,true)
                return
            }
            tracking = true
            everTracked = true
            lastPose = camera.pose
            lastPoseAt = now
            if (now - lastSample >= 350) {
                lastSample = now
                capture(frame, now)
            }
            while (keyframes.isNotEmpty() &&
                (now - keyframes.first().time > scanMode.keyframeLifetimeMs || keyframes.size > scanMode.maxLocalKeyframes))
                keyframes.removeFirst()
            if (now - lastPublish >= 250) {
                cachedWorld = worldSamples(now)
                val stride = max(1, cachedWorld.size/4000)
                pointBuffer = buffer(cachedWorld.filterIndexed { i, _ -> i % stride == 0 }
                    .flatMap { listOf(it.point.x,it.point.y,it.point.z) }.toFloatArray())
                val poseMatrix = FloatArray(16)
                camera.pose.toMatrix(poseMatrix, 0)
                val transform=CameraTransform(poseMatrix)
                // Publish the pose before the depth gate, not inside it. Tying "where am I" to
                // "is there usable depth" meant a phone aimed at a blank wall — perfectly tracked,
                // simply no depth — lost its position entirely, and destination navigation could
                // never start. The two are independent questions and must be answered separately.
                latestCamera=transform
                if (!depthGate.available(now)) {
                    emit(SpatialSnapshot(now, status = "定位已建立，正在等待可靠深度", phase=ScanPhase.WAITING_DEPTH), now)
                } else {
                    val rawCurrent=SpatialMap.build(cachedWorld,transform,now)
                    val current=rawCurrent.copy(objects=objectTracker.update(rawCurrent.objects,now))
                    val persistent=sessionGrid.update(current.grid,transform)
                    val guidance=LocalNavigator.advise(persistent)
                    val status=when {
                        current.floorHeight==null && persistent.isEmpty() -> current.status
                        else -> "已记住候选可走 ${persistent.count { it.state==TraversabilityState.FREE }} 格，障碍 ${persistent.count { it.state==TraversabilityState.OCCUPIED }} 格；灰色区域仍未知"
                    }
                    emit(current.copy(grid=persistent,status=status,guidance=guidance),now)
                }
            }
            if (depthGate.available(now)) drawPoints(camera)
        } catch (e: Exception) {
            // A single transient error must not end the session; only a sustained
            // failure does. This replaced a version that killed the whole session
            // on the first exception without recording what went wrong.
            val detail = "${e.javaClass.simpleName}: ${e.message ?: "no message"}"
            if (frameErrors == 0) firstFrameErrorAt = now
            frameErrors++
            android.util.Log.w("SpatialScan", "Frame failed #$frameErrors $detail", e)
            if (frameErrors >= 3 && now - firstFrameErrorAt >= 500) {
                frameErrors = 0
                clearMap(clearGrid=true)
                emit(SpatialSnapshot(now, status = "空间扫描中断，请返回后重试", phase=ScanPhase.ERROR), now)
            }
        }
    }

    private fun capture(frame: Frame, now: Long) {
        try {
            frame.acquireRawDepthImage16Bits().use { depth ->
                frame.acquireRawDepthConfidenceImage().use { confidence ->
                    // Confidence comes from this acquisition pair. ARCore does not promise equal
                    // Image timestamps across the two streams; only deduplicate the depth stream.
                    if (depth.width != confidence.width || depth.height != confidence.height) {
                        depthLog(now,"depth/confidence dimensions differ"); return
                    }
                    val intrinsics = frame.camera.textureIntrinsics
                    val dims = intrinsics.imageDimensions
                    val f = intrinsics.focalLength
                    val c = intrinsics.principalPoint
                    val sx = depth.width.toFloat()/dims[0]
                    val sy = depth.height.toFloat()/dims[1]
                    val dp = depth.planes[0]
                    val cp = confidence.planes[0]
                    val db = dp.buffer.order(ByteOrder.nativeOrder())
                    val cb = cp.buffer
                    val step = max(2, sqrt(depth.width*depth.height/1800.0).toInt())
                    val raw = mutableListOf<Pair<Point3, FloatArray>>()
                    var nonzero = 0
                    var confident = 0
                    for (y in 0 until depth.height step step) for (x in 0 until depth.width step step) {
                        val certainty = cb.get(y*cp.rowStride + x*cp.pixelStride).toInt() and 255
                        val z = (db.getShort(y*dp.rowStride + x*dp.pixelStride).toInt() and 65535)/1000f
                        if (z>0) nonzero++
                        if (certainty < 180) continue
                        confident++
                        val point = DepthGeometry.cameraPoint(x.toFloat(),y.toFloat(),z,f[0]*sx,f[1]*sy,c[0]*sx,c[1]*sy) ?: continue
                        raw += point to floatArrayOf(x.toFloat()/depth.width, y.toFloat()/depth.height)
                    }
                    depthLog(now,"depth=${depth.width}x${depth.height} nonzero=$nonzero confident=$confident valid=${raw.size} timestamps=${depth.timestamp}/${confidence.timestamp}/${frame.timestamp}")
                    // Valid reprojected depth keeps the current stream available, but must not create
                    // another keyframe or refresh timestamps of the remembered surfaces.
                    if (!depthGate.observe(depth.timestamp,raw.size,now)) return
                    var compactDepth:ShortArray?=null
                    var compactConfidence:ByteArray?=null
                    var poseMatrix:FloatArray?=null
                    var sharedFrameId=0L
                    if(publishOnlineFrame!=null || (publishColorFrame!=null&&scanMode==ScanMode.HOME)) {
                        val copiedDepth = ShortArray(depth.width * depth.height)
                        val copiedConfidence = ByteArray(depth.width * depth.height)
                        for (y in 0 until depth.height) for (x in 0 until depth.width) {
                            val index = y * depth.width + x
                            copiedDepth[index] = db.getShort(y * dp.rowStride + x * dp.pixelStride)
                            copiedConfidence[index] = cb.get(y * cp.rowStride + x * cp.pixelStride)
                        }
                        val copiedPose = FloatArray(16)
                        frame.camera.pose.toMatrix(copiedPose, 0)
                        sharedFrameId=++onlineFrameId
                        compactDepth = copiedDepth
                        compactConfidence = copiedConfidence
                        poseMatrix = copiedPose
                        publishOnlineFrame?.invoke(Online3dFrame(
                            sessionId = onlineSessionId,
                            frameId = sharedFrameId,
                            frameTimestampNs = frame.timestamp,
                            depthTimestampNs = depth.timestamp,
                            sentAtNs = System.nanoTime(),
                            persistentMap = scanMode.persistent,
                            width = depth.width,
                            height = depth.height,
                            fx = f[0] * sx,
                            fy = f[1] * sy,
                            cx = c[0] * sx,
                            cy = c[1] * sy,
                            cameraToWorld = copiedPose,
                            depthMillimeters = copiedDepth,
                            confidence = copiedConfidence,
                        ))
                    }
                    val tex = raw.flatMap { it.second.toList() }.toFloatArray()
                    val imageCoords = FloatArray(tex.size)
                    frame.transformCoordinates2d(Coordinates2d.TEXTURE_NORMALIZED, tex, Coordinates2d.IMAGE_NORMALIZED, imageCoords)
                    val samples = raw.mapIndexed { i, pair -> DepthImageSample(pair.first, imageCoords[2*i], imageCoords[2*i+1]) }
                    // Camera poses already share the session world coordinate system. Creating an
                    // ARCore Anchor for every short-lived depth keyframe adds no geometric value
                    // and caused Session.createAnchor() FatalException on the target phone.
                    val entry = Keyframe(frame.camera.pose, now, depth.timestamp, samples)
                    keyframes.addLast(entry)
                    val wantsDetection=busy.compareAndSet(false,true)
                    val wantsColor=scanMode==ScanMode.HOME&&publishColorFrame!=null&&colorBusy.compareAndSet(false,true)
                    if (wantsDetection||wantsColor) {
                        try {
                            val denseImageCoords=if(wantsColor){
                                val depthTex=FloatArray(depth.width*depth.height*2)
                                for(y in 0 until depth.height)for(x in 0 until depth.width){val i=(y*depth.width+x)*2;depthTex[i]=(x+.5f)/depth.width;depthTex[i+1]=(y+.5f)/depth.height}
                                FloatArray(depthTex.size).also{frame.transformCoordinates2d(Coordinates2d.TEXTURE_NORMALIZED,depthTex,Coordinates2d.IMAGE_NORMALIZED,it)}
                            }else null
                            // Copy plane bytes now; conversion and inference run on the worker thread.
                            val yuv = frame.acquireCameraImage().use { YuvFrame.from(it, imageRotation) }
                            val colorDepth=compactDepth
                            val colorConfidence=compactConfidence
                            val colorPose=poseMatrix
                            val colorFrameTimestamp=frame.timestamp
                            val colorDepthTimestamp=depth.timestamp
                            val colorWidth=depth.width;val colorHeight=depth.height
                            val colorFx=f[0]*sx;val colorFy=f[1]*sy;val colorCx=c[0]*sx;val colorCy=c[1]*sy
                            if(wantsColor&&denseImageCoords!=null&&colorDepth!=null&&colorConfidence!=null&&colorPose!=null) colorWorker.execute {
                                try {
                                    publishColorFrame?.invoke(Color3dFrame(onlineSessionId,sharedFrameId,colorFrameTimestamp,colorDepthTimestamp,yuv.timestampNs,System.nanoTime(),colorWidth,colorHeight,colorFx,colorFy,colorCx,colorCy,colorPose,colorDepth,colorConfidence,yuv.alignedRgb(denseImageCoords,colorWidth,colorHeight)))
                                } catch(e:Exception){android.util.Log.w("SpatialScan","Color RGB-D unavailable",e)}
                                finally{colorBusy.set(false)}
                            }
                            if(wantsDetection) worker.execute {
                                try {
                                    val bitmap = yuv.toBitmap()
                                    val model = detector ?: YoloDetector(app).also { detector = it }
                                    val detections = model.detect(bitmap)
                                    publishDetections?.invoke(detections, SystemClock.elapsedRealtime())
                                    entry.objects = SemanticDepthFusion.locate(detections, entry.samples, imageRotation)
                                } catch (e: Exception) { android.util.Log.w("SpatialScan", "Object labels unavailable", e) }
                                finally { busy.set(false) }
                            }
                        } catch (e: Exception) {
                            if(wantsDetection)busy.set(false)
                            if(wantsColor)colorBusy.set(false)
                            android.util.Log.w("SpatialScan", "Detector frame unavailable ${e.javaClass.simpleName}: ${e.message}")
                        }
                    }
                }
            }
        } catch (_: NotYetAvailableException) { depthLog(now,"raw depth not yet available") }
    }

    private fun depthLog(now: Long, text: String) {
        if(now-lastDepthLog >= 5000) { lastDepthLog=now; android.util.Log.i("SpatialDepth",text) }
    }

    private fun worldSamples(now: Long): List<WorldSample> = keyframes.flatMap { entry ->
        run {
            val matrix = FloatArray(16)
            entry.pose.toMatrix(matrix,0)
            val pose = CameraTransform(matrix)
            val surfaces = entry.samples.map { WorldSample(pose.world(it.point), entry.id, entry.time,
                cameraOrigin = pose.origin) }
            val objects = entry.objects.filter { observation ->
                observation.label !in setOf("行人", "汽车", "自行车", "摩托车", "公交车", "卡车", "猫", "狗", "鸟") || now-entry.time < 1200
            }.map { observation -> WorldSample(pose.world(observation.point), entry.id, entry.time,
                observation.label, classId=observation.classId, confidence=observation.confidence,
                depthSupport=observation.depthSupport, depthSpread=observation.depthSpread) }
            surfaces + objects
        }
    }

    private fun drawCamera(frame: Frame) {
        quad.position(0); uv.position(0)
        frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quad, Coordinates2d.TEXTURE_NORMALIZED, uv)
        glDisable(GL_DEPTH_TEST)
        glUseProgram(backgroundProgram)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        glUniform1i(glGetUniformLocation(backgroundProgram,"camera"),0)
        attribute(backgroundProgram,"p",2,quad)
        attribute(backgroundProgram,"uv",2,uv)
        glDrawArrays(GL_TRIANGLE_STRIP,0,4)
        glDisableVertexAttribArray(glGetAttribLocation(backgroundProgram,"p"))
        glDisableVertexAttribArray(glGetAttribLocation(backgroundProgram,"uv"))
    }

    private fun drawPoints(camera: Camera) {
        val points = pointBuffer ?: return
        if (points.limit()==0) return
        val view = FloatArray(16); val projection = FloatArray(16); val vp = FloatArray(16)
        camera.getViewMatrix(view,0); camera.getProjectionMatrix(projection,0,.1f,10f)
        Matrix.multiplyMM(vp,0,projection,0,view,0)
        glUseProgram(pointProgram)
        glUniformMatrix4fv(glGetUniformLocation(pointProgram,"vp"),1,false,vp,0)
        attribute(pointProgram,"p",3,points)
        glDrawArrays(GL_POINTS,0,points.limit()/3)
        glDisableVertexAttribArray(glGetAttribLocation(pointProgram,"p"))
    }

    private fun emit(snapshot: SpatialSnapshot, now: Long, force: Boolean = false) {
        if (force || now-lastPublish >= 250) { lastPublish=now; publish(snapshot) }
    }

    /** Marks "frames expected from now", so a fresh session is not judged stalled immediately. */
    fun markFrameClock() { lastFrameAtMs = SystemClock.elapsedRealtime() }

    /** Starts one isolated backend/model-library identity before AR frames are emitted. */
    fun beginOnlineSession():String {
        onlineSessionId=System.nanoTime();onlineFrameId=0L;captureSuspendedForPoseJump=false
        return java.lang.Long.toUnsignedString(onlineSessionId)
    }

    /**
     * Snapshot of everything this session has confirmed in world coordinates.
     *
     * Call only after `GLSurfaceView.onPause()` has stopped the render thread — the grid is
     * written from `onDrawFrame`, so reading it while frames are still flowing would race.
     */
    fun exportGrid(): List<TraversabilityCell> = sessionGrid.allCells()
    fun gridSize(): Int = sessionGrid.size()

    // Called only after GLSurfaceView.onPause has stopped the rendering thread.
    fun clearMap(resetSession: Boolean = false, clearGrid: Boolean = resetSession) {
        keyframes.clear()
        depthGate.reset(); lastPose=null; latestCamera=null; tracking=false
        cachedWorld=emptyList(); pointBuffer=null
        if(clearGrid) { sessionGrid.clear(); LocalNavigator.reset() }
        if(clearGrid) objectTracker.clear()
        if(resetSession) {
            everTracked=false; trackingLostAt=0L
            captureSuspendedForPoseJump=false
            onlineSessionId=System.nanoTime(); onlineFrameId=0L
        }
    }
    fun close() { worker.execute { detector?.close(); detector=null }; worker.shutdown() }

    companion object {
        /** How long tracking may drop before a capture is given up on. */
        private const val TRACKING_LOST_GRACE_MS = 3000L
        private fun poseJump(a: Pose, b: Pose): Float =
            hypot(hypot(a.tx()-b.tx(), a.ty()-b.ty()), a.tz()-b.tz())
        private fun buffer(values: FloatArray): FloatBuffer = ByteBuffer.allocateDirect(values.size*4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); position(0) }
        private fun attribute(program: Int, name: String, size: Int, data: FloatBuffer) {
            val index=glGetAttribLocation(program,name); data.position(0)
            glEnableVertexAttribArray(index); glVertexAttribPointer(index,size,GL_FLOAT,false,0,data)
        }
        private fun program(vertex: String, fragment: String): Int {
            fun shader(type: Int, source: String): Int {
                val shader=glCreateShader(type); glShaderSource(shader,source); glCompileShader(shader)
                val result=IntArray(1); glGetShaderiv(shader,GL_COMPILE_STATUS,result,0)
                check(result[0]!=0) { glGetShaderInfoLog(shader) }; return shader
            }
            val v=shader(GL_VERTEX_SHADER,vertex); val f=shader(GL_FRAGMENT_SHADER,fragment)
            val program=glCreateProgram(); glAttachShader(program,v); glAttachShader(program,f); glLinkProgram(program)
            glDeleteShader(v); glDeleteShader(f)
            val result=IntArray(1); glGetProgramiv(program,GL_LINK_STATUS,result,0)
            check(result[0]!=0) { glGetProgramInfoLog(program) }; return program
        }
    }
}
