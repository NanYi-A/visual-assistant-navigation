package cn.visualassistant.server

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

data class TsdfSample(var distance:Float=1f,var weight:Float=0f,var lastSeenNs:Long=0)
data class MeshTriangle(val a:Vec3,val b:Vec3,val c:Vec3,val normal:Vec3)
data class MeshSnapshot(val sessionId:Long,val revision:Long,val sourceFrameId:Long,val voxelSize:Float,val triangles:List<MeshTriangle>)

/** Sparse TSDF used only for the human-readable family model, never for safety decisions. */
class TsdfVolume(val voxelSize:Float=.08f,private val truncation:Float=voxelSize*3f,private val radius:Float=10f) {
    private val samples=HashMap<VoxelKey,TsdfSample>()
    var revision=0L;private set

    fun integrate(frame:Va3dFrame,pixelStride:Int=4) {
        val origin=Vec3(frame.cameraToWorld[12],frame.cameraToWorld[13],frame.cameraToWorld[14])
        val visited=HashSet<VoxelKey>()
        for(y in 0 until frame.height step pixelStride) for(x in 0 until frame.width step pixelStride) {
            val i=y*frame.width+x;val confidence=frame.confidence[i].toInt() and 255;if(confidence<180)continue
            val distance=(frame.depthMillimeters[i].toInt() and 65535)/1000f;if(distance<=.2f||distance>6f)continue
            val camera=Vec3((x-frame.cx)*distance/frame.fx,-(y-frame.cy)*distance/frame.fy,-distance)
            val surface=SparseVoxelMap.transform(frame.cameraToWorld,camera);val delta=surface-origin;val length=delta.length();if(length<=0f)continue
            val direction=delta*(1f/length);var t=max(.05f,length-truncation);val end=min(6f,length+truncation)
            while(t<=end){val p=origin+direction*t;val key=key(p);if(visited.add(key))update(key,((length-t)/truncation).coerceIn(-1f,1f),confidence/255f,frame.depthTimestampNs);t+=voxelSize}
        }
        val radius2=radius*radius;samples.entries.removeIf{(k,_)->val p=position(k);val d=p-origin;d.x*d.x+d.y*d.y+d.z*d.z>radius2}
        revision++
    }

    internal fun setSample(key:VoxelKey,distance:Float,weight:Float=1f){samples[key]=TsdfSample(distance.coerceIn(-1f,1f),weight,0)}
    private fun update(key:VoxelKey,distance:Float,weight:Float,timeNs:Long){val s=samples.getOrPut(key){TsdfSample()};val total=(s.weight+weight).coerceAtMost(24f);s.distance=(s.distance*s.weight+distance*weight)/(s.weight+weight);s.weight=total;s.lastSeenNs=timeNs}
    private fun key(p:Vec3)=VoxelKey(floor(p.x/voxelSize).toInt(),floor(p.y/voxelSize).toInt(),floor(p.z/voxelSize).toInt())
    private fun position(k:VoxelKey)=Vec3(k.x*voxelSize,k.y*voxelSize,k.z*voxelSize)

    fun extract(sessionId:Long,sourceFrameId:Long,maxTriangles:Int=30_000,minWeight:Float=.35f):MeshSnapshot {
        val triangles=ArrayList<MeshTriangle>(min(maxTriangles,4096));val corners=arrayOf(
            intArrayOf(0,0,0),intArrayOf(1,0,0),intArrayOf(1,1,0),intArrayOf(0,1,0),
            intArrayOf(0,0,1),intArrayOf(1,0,1),intArrayOf(1,1,1),intArrayOf(0,1,1))
        val tetra=arrayOf(intArrayOf(0,5,1,6),intArrayOf(0,1,2,6),intArrayOf(0,2,3,6),intArrayOf(0,3,7,6),intArrayOf(0,7,4,6),intArrayOf(0,4,5,6))
        loop@ for(base in samples.keys) {
            val keys=corners.map{VoxelKey(base.x+it[0],base.y+it[1],base.z+it[2])};val values=keys.map{samples[it]}
            if(values.any{it==null||it.weight<minWeight})continue
            val points=keys.map(::position);val distances=values.map{it!!.distance}
            for(t in tetra){polygonise(t,points,distances,triangles,maxTriangles);if(triangles.size>=maxTriangles)break@loop}
        }
        return MeshSnapshot(sessionId,revision,sourceFrameId,voxelSize,triangles)
    }

    private fun polygonise(t:IntArray,p:List<Vec3>,d:List<Float>,out:MutableList<MeshTriangle>,limit:Int) {
        val inside=t.filter{d[it]<0f};val outside=t.filter{d[it]>=0f};if(inside.isEmpty()||outside.isEmpty())return
        fun edge(a:Int,b:Int):Vec3 {val da=d[a];val db=d[b];val u=(da/(da-db)).coerceIn(0f,1f);return p[a]+(p[b]-p[a])*u}
        fun add(a:Vec3,b:Vec3,c:Vec3){if(out.size>=limit)return;val n=cross(b-a,c-a);val length=n.length();if(length>1e-6f)out+=MeshTriangle(a,b,c,n*(1f/length))}
        when(inside.size){
            1->{val i=inside[0];add(edge(i,outside[0]),edge(i,outside[1]),edge(i,outside[2]))}
            3->{val o=outside[0];add(edge(o,inside[0]),edge(o,inside[2]),edge(o,inside[1]))}
            2->{val i0=inside[0];val i1=inside[1];val a=edge(i0,outside[0]);val b=edge(i0,outside[1]);val c=edge(i1,outside[0]);val e=edge(i1,outside[1]);add(a,b,c);add(b,e,c)}
        }
    }
    private fun cross(a:Vec3,b:Vec3)=Vec3(a.y*b.z-a.z*b.y,a.z*b.x-a.x*b.z,a.x*b.y-a.y*b.x)
}
