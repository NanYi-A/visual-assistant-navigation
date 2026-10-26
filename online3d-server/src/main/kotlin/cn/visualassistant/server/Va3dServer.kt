package cn.visualassistant.server

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.BufferedOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bounded ingestion queue. When fusion is slower than capture the oldest frame
 * is dropped, so latency never grows with session length.
 */
class FrameQueue(private val capacity: Int = 1) {
    private val lock = Any()
    private val pending = ArrayDeque<ByteArray>()
    var droppedFrames = 0L
        private set

    fun offer(payload: ByteArray) {
        synchronized(lock) {
            pending.addLast(payload)
            while (pending.size > capacity) {
                pending.removeFirst()
                droppedFrames++
            }
        }
    }

    fun poll(): ByteArray? = synchronized(lock) { pending.removeFirstOrNull() }
    fun size(): Int = synchronized(lock) { pending.size }
}

object Va3dStream {
    /** Reads four-byte length-prefixed payloads until the stream closes. */
    fun readPayloads(input: InputStream, onPayload: (ByteArray) -> Unit) {
        val data = DataInputStream(input)
        while (true) {
            val size = try {
                data.readInt()
            } catch (_: EOFException) {
                return
            } catch (_: IOException) {
                return
            }
            if (size !in 8..Va3dProtocol.MAX_PACKET_BYTES) {
                throw IllegalArgumentException("invalid packet size $size")
            }
            val payload = ByteArray(size)
            data.readFully(payload)
            onPayload(payload)
        }
    }

    /** Wire format used by the phone: big-endian length prefix then the payload. */
    fun writePayload(output: OutputStream, payload: ByteArray) {
        val prefix = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(payload.size).array()
        output.write(prefix)
        output.write(payload)
        output.flush()
    }
}

/**
 * Accepts one phone connection at a time and feeds the newest frame to the voxel map.
 * Fusion runs on its own thread; the socket thread only enqueues bytes.
 */
class Va3dServer(
    port: Int,
    private val map: SparseVoxelMap,
    private val queue: FrameQueue = FrameQueue(),
    private val homeStore: VoxelMapStore? = null,
    private val saveEveryFrames: Int = 10,
    private val modelsRoot: Path? = null,
) : AutoCloseable {
    private val server = ServerSocket(port)
    private val running = AtomicBoolean(true)
    private val socketThread: Thread
    private val fusionThread: Thread
    val port: Int get() = server.localPort
    val droppedFrames: Long get() = queue.droppedFrames
    val pendingFrames: Int get() = queue.size()
    var integratedFrames = 0L
        private set
    var publishedUpdates = 0L
        private set
    var publishFailures = 0L
        private set
    private val outputLock=Any()
    @Volatile private var clientOutput:OutputStream?=null
    private val deltaTracker=MapDeltaTracker()
    private var homeMap=map
    private var currentHomeStore=homeStore
    private var homeTsdf=TsdfVolume(map.voxelSize)
    private val outdoorMap=SparseVoxelMap(voxelSize=map.voxelSize)
    @Volatile private var activeMap:SparseVoxelMap=outdoorMap
    private var activeSessionId:Long?=null
    private var activePersistent:Boolean?=null
    @Volatile private var homeDirty = false
    val activeRevision:Long get()=activeMap.revision
    val activeOccupied:Int get()=activeMap.occupiedCount()
    val activeFree:Int get()=activeMap.freeCount()

    init {
        socketThread = Thread({
            while (running.get()) {
                try {
                    server.accept().use { socket ->
                        synchronized(outputLock){clientOutput=BufferedOutputStream(socket.getOutputStream(),256*1024);deltaTracker.reset()}
                        try { Va3dStream.readPayloads(socket.getInputStream(), queue::offer) }
                        finally { synchronized(outputLock){clientOutput=null} }
                    }
                } catch (_: IOException) {
                    if (!running.get()) break
                    Thread.sleep(50)
                }
            }
        }, "va3d-socket").apply { isDaemon = true }

        fusionThread = Thread({
            while (running.get()) {
                val payload = queue.poll()
                if (payload == null) {
                    Thread.sleep(2)
                    continue
                }
                if(Va3dProtocol.messageType(payload)==Va3dProtocol.MESSAGE_FINISH_HOME){
                    val submitted=runCatching{Va3dProtocol.finishSession(payload)}.getOrNull()?:continue
                    if(activeSessionId==submitted){saveHome();modelsRoot?.let{root->runCatching{NavigationModelBuilder.write(NavigationModelBuilder.build(activeMap),root.resolve(java.lang.Long.toUnsignedString(submitted)).resolve("cognition").resolve("navigation.json"))}}}
                    continue
                }
                if(Va3dProtocol.messageType(payload)==Va3dProtocol.MESSAGE_START_SEGMENT){
                    // Geometry fusion stays isolated until the offline relocalizer validates alignment.
                    runCatching{Va3dProtocol.segmentSessions(payload)}
                    continue
                }
                val frame = runCatching { Va3dProtocol.decode(payload) }.getOrNull() ?: continue
                val sessionChanged=activeSessionId!=frame.sessionId
                val modeChanged=activePersistent!=frame.persistentMap
                if(sessionChanged||modeChanged){
                    if(!frame.persistentMap) outdoorMap.restore(emptyMap(),0,-1)
                    else {
                        if(sessionChanged&&modelsRoot!=null){
                            saveHome()
                            homeMap=SparseVoxelMap(voxelSize=map.voxelSize)
                            homeTsdf=TsdfVolume(map.voxelSize)
                            val modelId=java.lang.Long.toUnsignedString(frame.sessionId)
                            currentHomeStore=VoxelMapStore(modelsRoot.resolve(modelId).resolve("cognition").resolve("occupancy.vamap"))
                        }
                        homeMap.resetDepthClock()
                    }
                    activeMap=if(frame.persistentMap) homeMap else outdoorMap
                    activeSessionId=frame.sessionId;activePersistent=frame.persistentMap
                    deltaTracker.reset()
                }
                if (activeMap.integrate(frame)) {
                    integratedFrames++
                    if (frame.persistentMap) {
                        homeTsdf.integrate(frame)
                        homeDirty = true
                        if (integratedFrames % saveEveryFrames == 0L) saveHome()
                    }
                    publish(MapProtocol.encode(deltaTracker.next(frame.sessionId,frame.frameId,activeMap,frame.cameraToWorld,forceSnapshot=modeChanged||sessionChanged)))
                    if(frame.persistentMap&&integratedFrames%8L==0L)publish(MeshProtocol.encode(homeTsdf.extract(frame.sessionId,frame.frameId)))
                }
            }
        }, "va3d-fusion").apply { isDaemon = true }

        socketThread.start()
        fusionThread.start()
    }

    override fun close() {
        running.set(false)
        server.close()
        socketThread.interrupt()
        fusionThread.interrupt()
        socketThread.join(2_000)
        fusionThread.join(2_000)
        saveHome()
    }

    @Synchronized private fun saveHome() {
        if (!homeDirty) return
        currentHomeStore?.save(homeMap)
        homeDirty = false
    }
    private fun publish(payload:ByteArray){synchronized(outputLock){val output=clientOutput?:return;try{Va3dStream.writePayload(output,payload);publishedUpdates++}catch(_:IOException){publishFailures++;clientOutput=null}}}
}
