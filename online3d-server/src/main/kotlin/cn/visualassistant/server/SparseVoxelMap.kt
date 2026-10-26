package cn.visualassistant.server

import kotlin.math.*

data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vec3)=Vec3(x+o.x,y+o.y,z+o.z)
    operator fun minus(o: Vec3)=Vec3(x-o.x,y-o.y,z-o.z)
    operator fun times(s: Float)=Vec3(x*s,y*s,z*s)
    fun length()=sqrt(x*x+y*y+z*z)
}
data class VoxelKey(val x:Int,val y:Int,val z:Int)
enum class VoxelState { RELIABLE_FREE, RELIABLE_OCCUPIED, TENTATIVE, CONFLICT }

data class VoxelEvidence(
    var score:Int=0, var occupiedObservations:Int=0, var freeObservations:Int=0,
    var confidenceSum:Long=0, var confidenceSquareSum:Long=0, var viewMask:Long=0,
    var lastFrameId:Long=0, var lastOccupiedFrameId:Long=Long.MIN_VALUE,
    var lastFreeFrameId:Long=Long.MIN_VALUE, var lastSeenNs:Long=0,
) {
    val viewCount get()=java.lang.Long.bitCount(viewMask)
    val meanConfidence get()=if(occupiedObservations==0) 0.0 else confidenceSum.toDouble()/occupiedObservations
    val confidenceVariance get():Double { val m=meanConfidence; return if(occupiedObservations<2) 0.0 else confidenceSquareSum.toDouble()/occupiedObservations-m*m }
    fun state()=when {
        occupiedObservations>=2 && freeObservations>=2 && abs(score)<=3 -> VoxelState.CONFLICT
        score>=6 && viewCount>=2 && meanConfidence>=180 -> VoxelState.RELIABLE_OCCUPIED
        score<=-4 && freeObservations>=2 -> VoxelState.RELIABLE_FREE
        else -> VoxelState.TENTATIVE
    }
}
data class MaintenanceResult(val removedOutsideRadius:Int,val removedExpired:Int,val decayed:Int)

class SparseVoxelMap(
    val voxelSize:Float=.08f, private val minConfidence:Int=180, private val maxDistance:Float=6f,
    private val mapRadius:Float=10f, private val outdoorLifetimeNs:Long=12_000_000_000L,
    private val decayAfterNs:Long=3_000_000_000L,
) {
    private val cells=HashMap<VoxelKey,VoxelEvidence>()
    var revision:Long=0; internal set
    var lastDepthTimestampNs:Long=-1; internal set

    fun integrate(frame:Va3dFrame,pixelStride:Int=3):Boolean {
        require(pixelStride>0); if(frame.depthTimestampNs<=lastDepthTimestampNs)return false
        lastDepthTimestampNs=frame.depthTimestampNs
        val origin=Vec3(frame.cameraToWorld[12],frame.cameraToWorld[13],frame.cameraToWorld[14])
        for(y in 0 until frame.height step pixelStride) for(x in 0 until frame.width step pixelStride) {
            val i=y*frame.width+x; val confidence=frame.confidence[i].toInt() and 255
            if(confidence<minConfidence)continue
            val distance=(frame.depthMillimeters[i].toInt() and 65535)/1000f
            if(distance<=.15f||distance>maxDistance)continue
            val camera=Vec3((x-frame.cx)*distance/frame.fx,-(y-frame.cy)*distance/frame.fy,-distance)
            integrateRay(origin,transform(frame.cameraToWorld,camera),confidence,frame.frameId,frame.depthTimestampNs)
        }
        revision++; maintain(origin,frame.depthTimestampNs,frame.persistentMap); return true
    }

    internal fun integrateRay(origin:Vec3,surface:Vec3,confidence:Int,frameId:Long,timeNs:Long) {
        val delta=surface-origin; val length=delta.length(); if(length<=0)return
        val direction=delta*(1f/length); val freeEnd=max(0f,length-voxelSize*1.5f)
        val visited=HashSet<VoxelKey>(); var distance=0f; val step=voxelSize*.75f
        while(distance<freeEnd){ val k=key(origin+direction*distance); if(visited.add(k))updateFree(k,frameId,timeNs); distance+=step }
        updateOccupied(key(surface),origin,surface,confidence,frameId,timeNs)
    }
    private fun updateFree(k:VoxelKey,frameId:Long,timeNs:Long){
        val e=cells.getOrPut(k){VoxelEvidence()}; if(e.lastFreeFrameId==frameId)return
        e.score=(e.score-2).coerceIn(-12,12); e.freeObservations++; e.lastFreeFrameId=frameId; e.lastFrameId=frameId; e.lastSeenNs=timeNs
    }
    private fun updateOccupied(k:VoxelKey,origin:Vec3,surface:Vec3,confidence:Int,frameId:Long,timeNs:Long){
        val e=cells.getOrPut(k){VoxelEvidence()}; if(e.lastOccupiedFrameId==frameId)return
        e.score=(e.score+3).coerceIn(-12,12); e.occupiedObservations++; e.confidenceSum+=confidence
        e.confidenceSquareSum+=confidence.toLong()*confidence; e.viewMask=e.viewMask or (1L shl viewBin(origin,surface))
        e.lastOccupiedFrameId=frameId; e.lastFrameId=frameId; e.lastSeenNs=timeNs
    }

    fun maintain(center:Vec3,nowNs:Long,persistent:Boolean):MaintenanceResult {
        var outside=0; var expired=0; var decayed=0; val radius2=mapRadius*mapRadius; val it=cells.iterator()
        while(it.hasNext()) { val (k,e)=it.next(); val p=center(k); val dx=p.x-center.x;val dy=p.y-center.y;val dz=p.z-center.z
            if(dx*dx+dy*dy+dz*dz>radius2){it.remove();outside++;continue}; if(persistent)continue
            val age=nowNs-e.lastSeenNs; if(age>outdoorLifetimeNs){it.remove();expired++;continue}
            if(age>decayAfterNs&&e.state()==VoxelState.TENTATIVE&&e.score!=0){e.score+=if(e.score>0)-1 else 1;decayed++
                if(e.score==0&&e.occupiedObservations<2&&e.freeObservations<2){it.remove();expired++}}
        }
        if(outside+expired+decayed>0)revision++; return MaintenanceResult(outside,expired,decayed)
    }

    fun key(p:Vec3)=VoxelKey(floor(p.x/voxelSize).toInt(),floor(p.y/voxelSize).toInt(),floor(p.z/voxelSize).toInt())
    fun center(k:VoxelKey)=Vec3((k.x+.5f)*voxelSize,(k.y+.5f)*voxelSize,(k.z+.5f)*voxelSize)
    fun evidence(k:VoxelKey)=cells[k]
    fun snapshot():Map<VoxelKey,VoxelEvidence> = cells.mapValues{it.value.copy()}
    internal fun restore(items:Map<VoxelKey,VoxelEvidence>,restoredRevision:Long,depthTimestampNs:Long){cells.clear();items.forEach{cells[it.key]=it.value.copy()};revision=restoredRevision;lastDepthTimestampNs=depthTimestampNs}
    internal fun resetDepthClock(){lastDepthTimestampNs=-1}
    fun occupiedCount()=cells.count{it.value.state()==VoxelState.RELIABLE_OCCUPIED||it.value.score>=3}
    fun freeCount()=cells.count{it.value.state()==VoxelState.RELIABLE_FREE}
    fun stateCounts()=VoxelState.entries.associateWith{s->cells.count{it.value.state()==s}}
    private fun viewBin(origin:Vec3,surface:Vec3):Int { val d=origin-surface;val a=floor(((atan2(d.z,d.x)+PI)/(2*PI))*8).toInt().coerceIn(0,7);val e=floor(((atan2(d.y,sqrt(d.x*d.x+d.z*d.z))+PI/2)/PI)*8).toInt().coerceIn(0,7);return e*8+a }
    companion object { fun transform(m:FloatArray,p:Vec3)=Vec3(m[0]*p.x+m[4]*p.y+m[8]*p.z+m[12],m[1]*p.x+m[5]*p.y+m[9]*p.z+m[13],m[2]*p.x+m[6]*p.y+m[10]*p.z+m[14]) }
}
