package cn.visualassistant.app.online3d

import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class RemotePose3(val x:Float,val y:Float,val z:Float,val forwardX:Float,val forwardY:Float,val forwardZ:Float) {
    fun distanceSquared(other:RemotePose3):Float {val dx=x-other.x;val dy=y-other.y;val dz=z-other.z;return dx*dx+dy*dy+dz*dz}
}

data class RemoteMapQuality(
    val total:Int,
    val reliable:Int,
    val tentative:Int,
    val conflict:Int,
    val reliabilityPercent:Int,
    val rescanHint:String?,
) {
    val displayText:String get() = "已建模$total 格 · 可靠 $reliabilityPercent% · 暂定 $tentative · 冲突 $conflict"+
        (rescanHint?.let{" · $it"}?:"")
}

/** A conservative report over observed cells; it never calls unknown house volume scanned. */
object RemoteMapQualityAnalyzer {
    fun analyze(voxels:List<RemoteVoxel>,voxelSize:Float,pose:RemotePose3?):RemoteMapQuality {
        val reliable=voxels.count{it.state==RemoteVoxelState.RELIABLE_FREE||it.state==RemoteVoxelState.RELIABLE_OCCUPIED}
        val tentative=voxels.count{it.state==RemoteVoxelState.TENTATIVE}
        val conflict=voxels.count{it.state==RemoteVoxelState.CONFLICT}
        val hotspotCandidates=voxels.asSequence().filter{it.state==RemoteVoxelState.CONFLICT}.toList()
            .ifEmpty{voxels.filter{it.state==RemoteVoxelState.TENTATIVE}}
        val hotspot=if(pose==null) null else hotspotCandidates.minByOrNull{
            val dx=(it.x+.5f)*voxelSize-pose.x;val dy=(it.y+.5f)*voxelSize-pose.y;val dz=(it.z+.5f)*voxelSize-pose.z
            dx*dx+dy*dy+dz*dz
        }
        val hint=if(pose==null||hotspot==null) null else describe(hotspot,voxelSize,pose,conflict>0)
        return RemoteMapQuality(voxels.size,reliable,tentative,conflict,
            if(voxels.isEmpty())0 else (reliable*100f/voxels.size).roundToInt(),hint)
    }

    private fun describe(voxel:RemoteVoxel,size:Float,pose:RemotePose3,conflict:Boolean):String {
        val dx=(voxel.x+.5f)*size-pose.x;val dy=(voxel.y+.5f)*size-pose.y;val dz=(voxel.z+.5f)*size-pose.z
        val distance=sqrt(dx*dx+dy*dy+dz*dz)
        val heading=atan2(pose.forwardX.toDouble(),pose.forwardZ.toDouble())
        val target=atan2(dx.toDouble(),dz.toDouble())
        var angle=(target-heading)*180/Math.PI
        while(angle>180) angle-=360.0;while(angle< -180) angle+=360.0
        val direction=when { angle< -25&&angle> -150->"右前方";angle>25&&angle<150->"左前方";kotlin.math.abs(angle)>=150->"后方";else->"前方" }
        return "${direction}约${"%.1f".format(distance)}米有${if(conflict)"冲突区域" else "暂定区域"}，请补扫"
    }
}
