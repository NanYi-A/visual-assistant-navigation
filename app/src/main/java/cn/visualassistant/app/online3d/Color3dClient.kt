package cn.visualassistant.app.online3d

import android.util.Log
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/** Independent best-effort RGB-D display channel; it can fail without affecting navigation. */
class Color3dClient(private var host:String,private var port:Int=38401,
    private val status:(String)->Unit={},private val meshUpdate:(RemoteColoredMesh)->Unit={},private val localizationUpdate:(RemoteLocalizationResult)->Unit={},
    private val optimizedMeshUpdate:(RemoteColoredMesh)->Unit={},private val modelStatusUpdate:(RemoteModelStatus)->Unit={}):AutoCloseable {
    private val running=AtomicBoolean(true);private val queue=ArrayBlockingQueue<ByteArray>(2)
    private val thread=Thread(::loop,"color-3d-uplink").apply{start()}
    @Volatile private var activeSocket:Socket?=null
    /** Point the client at a different computer; the address moves when it rejoins the hotspot. */
    @Synchronized fun retarget(newHost:String,newPort:Int=port){
        if(newHost.isBlank()||(newHost==host&&newPort==port))return
        host=newHost;port=newPort
        runCatching{ activeSocket?.close() }
    }
    fun offer(frame:Color3dFrame){if(running.get()){if(queue.peek()?.let{it.size>=8&&it[7].toInt()==HomeControlProtocol.TYPE_START_SEGMENT.toInt()}==true){queue.offer(Color3dProtocol.encode(frame));return};queue.poll();queue.offer(Color3dProtocol.encode(frame))}}
    private fun control(payload:ByteArray){queue.clear();queue.offer(payload)}
    fun finishHome(sessionId:Long,places:List<ConfirmedPlacePacket>){control(HomeControlProtocol.finish(sessionId,places))}
    fun startSegment(sessionId:Long,parentSessionId:Long){control(HomeControlProtocol.startSegment(sessionId,parentSessionId))}
    fun requestOptimized(sessionId:Long){control(Color3dProtocol.requestOptimized(sessionId))}
    private fun loop(){var warned=false;while(running.get()){
        val first=try{queue.take()}catch(_:InterruptedException){break}
        try{Socket().use{socket->activeSocket=socket;socket.tcpNoDelay=true;socket.keepAlive=true;socket.connect(InetSocketAddress(host,port),1200)
            val output=BufferedOutputStream(socket.getOutputStream(),512*1024);status("彩色三维电脑已连接");warned=false
            val reader=Thread({read(socket)},"color-3d-downlink").apply{isDaemon=true;start()};var payload:ByteArray?=first
            while(running.get()&&payload!=null){output.write(Online3dProtocol.lengthPrefix(payload.size));output.write(payload);output.flush();payload=queue.take()}
            reader.interrupt()
        }}catch(e:Exception){if(!warned)Log.w("Color3d","channel unavailable",e);if(!warned)status("彩色三维电脑未连接，安全导航仍可使用");warned=true;if(running.get())try{Thread.sleep(700)}catch(_:InterruptedException){break}}
        finally{activeSocket=null}
    }}
    private fun read(socket:Socket){try{val input=DataInputStream(socket.getInputStream());while(running.get()&&!socket.isClosed){val size=input.readInt();require(size in 20..Online3dProtocol.MAX_PACKET_BYTES);val payload=ByteArray(size);input.readFully(payload);when(Color3dProtocol.messageType(payload)){Color3dProtocol.TYPE_LOCALIZATION->localizationUpdate(Color3dProtocol.decodeLocalization(payload));Color3dProtocol.TYPE_MODEL_STATUS->modelStatusUpdate(Color3dProtocol.decodeModelStatus(payload));Color3dProtocol.TYPE_OPTIMIZED_MESH->optimizedMeshUpdate(Color3dProtocol.decodeMesh(payload));else->meshUpdate(Color3dProtocol.decodeMesh(payload))}}}catch(e:Exception){if(running.get())Log.w("Color3d","mesh downlink unavailable",e)}}
    override fun close(){running.set(false);thread.interrupt()}
}
