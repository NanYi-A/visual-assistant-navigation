package cn.visualassistant.app.online3d

import android.util.Log
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/** Latest-frame TCP sender. Capture never blocks behind a slow network. */
class Online3dClient(
    private var host: String,
    private var port: Int = 38400,
    private val status: (String) -> Unit = {},
    private val mapUpdate: (RemoteMapPacket) -> Unit = {},
    private val meshUpdate: (RemoteMesh) -> Unit = {},
) : AutoCloseable {
    private val running = AtomicBoolean(true)
    private val queue = ArrayBlockingQueue<ByteArray>(2)
    private val thread = Thread(::runLoop, "online-3d-uplink").apply { start() }
    @Volatile private var activeSocket:Socket?=null

    /**
     * Point the client at a different computer.
     *
     * The address of the computer changes whenever it reconnects to the phone's hotspot, and
     * the client has to be able to follow it without the whole page being rebuilt.
     */
    @Synchronized fun retarget(newHost: String, newPort: Int = port) {
        if (newHost.isBlank() || (newHost == host && newPort == port)) return
        host = newHost
        port = newPort
        // Dropping the socket makes the loop reconnect to the new address.
        runCatching { activeSocket?.close() }
    }

    fun offer(frame: Online3dFrame) {
        if (!running.get()) return
        if(queue.peek()?.let{it.size>=8&&it[7].toInt()==HomeControlProtocol.TYPE_START_SEGMENT.toInt()}==true){queue.offer(Online3dProtocol.encode(frame));return}
        queue.poll() // latest frame wins; accumulated latency is worse than a dropped frame
        queue.offer(Online3dProtocol.encode(frame))
    }
    private fun control(payload:ByteArray){queue.clear();queue.offer(payload)}
    fun finishHome(sessionId:Long,places:List<ConfirmedPlacePacket>){control(HomeControlProtocol.finish(sessionId,places))}
    fun startSegment(sessionId:Long,parentSessionId:Long){control(HomeControlProtocol.startSegment(sessionId,parentSessionId))}

    private fun runLoop() {
        var announcedFailure = false
        while (running.get()) {
            val first = try { queue.take() } catch (_: InterruptedException) { break }
            try {
                Socket().use { socket ->
                    activeSocket=socket
                    socket.tcpNoDelay = true
                    socket.keepAlive = true
                    socket.connect(InetSocketAddress(host, port), 1200)
                    val output = BufferedOutputStream(socket.getOutputStream(), 256 * 1024)
                    status("在线三维电脑已连接")
                    val reader=Thread({readUpdates(socket)},"online-3d-downlink").apply{isDaemon=true;start()}
                    announcedFailure = false
                    var payload: ByteArray? = first
                    while (running.get() && payload != null) {
                        output.write(Online3dProtocol.lengthPrefix(payload.size))
                        output.write(payload)
                        output.flush()
                        payload = queue.take()
                    }
                    reader.interrupt()
                }
            } catch (e: Exception) {
                // Only the transition is worth a stack trace. With a 1.2s connect timeout this loop
                // spins roughly once a second, and a full trace every second drowns the entire
                // logcat — which is exactly how a dead backend went unnoticed until now.
                if (!announcedFailure) Log.w("Online3d", "uplink unavailable: ${e.javaClass.simpleName}: ${e.message}")
                if (!announcedFailure) status("在线三维电脑未连接，继续使用本地模式")
                announcedFailure = true
                if (running.get()) try { Thread.sleep(700) } catch (_: InterruptedException) { break }
            } finally { activeSocket=null }
            }
        }

    private fun readUpdates(socket:Socket){
        try { val input=DataInputStream(socket.getInputStream());while(running.get()&&!socket.isClosed){val size=input.readInt();require(size in 40..Online3dProtocol.MAX_PACKET_BYTES);val payload=ByteArray(size);input.readFully(payload);if(RemoteMeshProtocol.messageType(payload)==RemoteMeshProtocol.TYPE)meshUpdate(RemoteMeshProtocol.decode(payload))else mapUpdate(RemoteMapProtocol.decode(payload))} }
        catch(e:Exception){if(running.get())Log.w("Online3d","downlink unavailable",e)}
    }

    /** Reconnecting makes the server send a fresh snapshot after the next frame. */
    fun requestSnapshot(){runCatching{activeSocket?.close()}}

    override fun close() {
        running.set(false)
        thread.interrupt()
    }
}
