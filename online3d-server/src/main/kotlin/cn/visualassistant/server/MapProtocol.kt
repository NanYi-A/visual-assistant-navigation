package cn.visualassistant.server

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

data class MapVoxel(val key:VoxelKey,val state:VoxelState,val confidence:Int,val observations:Int,val updatedAtMs:Long,val surfaceMask:Int=0)
data class MapUpdate(val snapshot:Boolean,val sessionId:Long,val baseRevision:Long,val revision:Long,val sourceFrameId:Long,val voxelSize:Float,val changed:List<MapVoxel>,val removed:List<VoxelKey>,val cameraPosition:FloatArray=floatArrayOf(0f,0f,0f),val cameraForward:FloatArray=floatArrayOf(0f,0f,-1f))

object MapProtocol {
    const val MESSAGE_DELTA=2;const val MESSAGE_SNAPSHOT=3;const val MAX_UPDATE_BYTES=4*1024*1024
    fun encode(update:MapUpdate):ByteArray {
        require(update.revision>0);require(update.baseRevision>=0);require(update.voxelSize>0)
        require(update.cameraPosition.size==3&&update.cameraForward.size==3)
        require(update.cameraPosition.all(Float::isFinite)&&update.cameraForward.all(Float::isFinite))
        val expected=76+update.changed.size*25+update.removed.size*12;require(expected<=MAX_UPDATE_BYTES)
        val bytes=ByteArrayOutputStream(expected)
        DataOutputStream(bytes).use{out->
            out.writeInt(Va3dProtocol.MAGIC);out.writeShort(Va3dProtocol.VERSION);out.writeShort(if(update.snapshot)MESSAGE_SNAPSHOT else MESSAGE_DELTA)
            out.writeLong(update.sessionId);out.writeLong(update.baseRevision);out.writeLong(update.revision);out.writeLong(update.sourceFrameId)
            out.writeFloat(update.voxelSize);out.writeInt(update.changed.size);out.writeInt(update.removed.size)
            update.cameraPosition.forEach(out::writeFloat);update.cameraForward.forEach(out::writeFloat)
            update.changed.forEach{v->out.writeInt(v.key.x);out.writeInt(v.key.y);out.writeInt(v.key.z);out.writeByte(v.state.ordinal);out.writeByte(v.confidence.coerceIn(0,255));out.writeShort(v.observations.coerceIn(0,65535));out.writeLong(v.updatedAtMs);out.writeByte(v.surfaceMask and 0x3f)}
            update.removed.forEach{k->out.writeInt(k.x);out.writeInt(k.y);out.writeInt(k.z)}
        }
        return bytes.toByteArray().also{check(it.size==expected)}
    }
    fun snapshot(sessionId:Long,sourceFrameId:Long,map:SparseVoxelMap):MapUpdate {val cells=map.snapshot();return MapUpdate(true,sessionId,0,map.revision,sourceFrameId,map.voxelSize,cells.map{(k,e)->voxel(k,e,surfaceMask(k,e,cells))},emptyList())}
    fun voxel(key:VoxelKey,e:VoxelEvidence,surfaceMask:Int=0)=MapVoxel(key,e.state(),e.meanConfidence.toInt().coerceIn(0,255),e.occupiedObservations+e.freeObservations,e.lastSeenNs/1_000_000,surfaceMask)
    fun surfaceMask(key:VoxelKey,e:VoxelEvidence,cells:Map<VoxelKey,VoxelEvidence>):Int {
        if(e.score<3)return 0
        val neighbours=arrayOf(VoxelKey(key.x-1,key.y,key.z),VoxelKey(key.x+1,key.y,key.z),VoxelKey(key.x,key.y-1,key.z),VoxelKey(key.x,key.y+1,key.z),VoxelKey(key.x,key.y,key.z-1),VoxelKey(key.x,key.y,key.z+1))
        var mask=0;neighbours.forEachIndexed{i,k->if((cells[k]?.score?:Int.MIN_VALUE)<3)mask=mask or (1 shl i)};return mask
    }
}

/** Computes revision-continuous updates without exposing mutable map internals. */
class MapDeltaTracker {
    private var sessionId:Long?=null;private var revision=0L;private var previous=emptyMap<VoxelKey,MapVoxel>()
    fun next(activeSessionId:Long,sourceFrameId:Long,map:SparseVoxelMap,cameraToWorld:FloatArray?=null,forceSnapshot:Boolean=false):MapUpdate {
        val evidence=map.snapshot()
        val current=evidence.mapValues{MapProtocol.voxel(it.key,it.value,MapProtocol.surfaceMask(it.key,it.value,evidence))}
        val snapshot=forceSnapshot||sessionId!=activeSessionId||revision==0L
        val position=cameraToWorld?.let{floatArrayOf(it[12],it[13],it[14])}?:floatArrayOf(0f,0f,0f)
        val forward=cameraToWorld?.let{floatArrayOf(-it[8],-it[9],-it[10])}?:floatArrayOf(0f,0f,-1f)
        val update=if(snapshot) MapUpdate(true,activeSessionId,0,map.revision,sourceFrameId,map.voxelSize,current.values.toList(),emptyList(),position,forward)
        else MapUpdate(false,activeSessionId,revision,map.revision,sourceFrameId,map.voxelSize,current.filter{previous[it.key]!=it.value}.values.toList(),previous.keys.filter{it !in current},position,forward)
        sessionId=activeSessionId;revision=map.revision;previous=current;return update
    }
    fun reset(){sessionId=null;revision=0;previous=emptyMap()}
}
