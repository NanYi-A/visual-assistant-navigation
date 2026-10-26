package cn.visualassistant.app.online3d

enum class RemoteVoxelState { RELIABLE_FREE, RELIABLE_OCCUPIED, TENTATIVE, CONFLICT }

data class RemoteVoxel(
    val x: Int,
    val y: Int,
    val z: Int,
    val state: RemoteVoxelState,
    val confidence: Int,
    val observations: Int,
    val updatedAtMs: Long,
    val surfaceMask:Int = 0,
)

data class RemoteMapDelta(
    val sessionId: Long,
    val baseRevision: Long,
    val revision: Long,
    val sourceFrameId: Long,
    val changed: List<RemoteVoxel>,
    val removed: List<VoxelKey> = emptyList(),
)

data class VoxelKey(val x: Int, val y: Int, val z: Int)

sealed interface DeltaResult {
    data object Applied : DeltaResult
    data object WrongSession : DeltaResult
    data object SnapshotRequired : DeltaResult
    data object Stale : DeltaResult
}

/** Revision-checked phone-side display cache. It never fills gaps with guessed geometry. */
class RemoteVoxelMap {
    private val voxels = HashMap<VoxelKey, RemoteVoxel>()
    var sessionId: Long? = null
        private set
    var revision: Long = 0
        private set
    var sourceFrameId: Long = 0
        private set

    fun beginSession(newSessionId: Long) {
        voxels.clear()
        sessionId = newSessionId
        revision = 0
        sourceFrameId = 0
    }

    fun apply(delta: RemoteMapDelta): DeltaResult {
        val active = sessionId ?: return DeltaResult.WrongSession
        if (delta.sessionId != active) return DeltaResult.WrongSession
        if (delta.revision <= revision) return DeltaResult.Stale
        if (delta.baseRevision != revision) return DeltaResult.SnapshotRequired
        delta.removed.forEach(voxels::remove)
        delta.changed.forEach { voxel ->
            require(voxel.confidence in 0..255)
            require(voxel.observations >= 0)
            voxels[VoxelKey(voxel.x, voxel.y, voxel.z)] = voxel
        }
        revision = delta.revision
        sourceFrameId = maxOf(sourceFrameId, delta.sourceFrameId)
        return DeltaResult.Applied
    }

    fun replaceSnapshot(snapshot: RemoteMapDelta): DeltaResult {
        if (snapshot.baseRevision != 0L || snapshot.revision <= 0L) return DeltaResult.SnapshotRequired
        beginSession(snapshot.sessionId)
        snapshot.changed.forEach { voxel ->
            require(voxel.confidence in 0..255)
            voxels[VoxelKey(voxel.x, voxel.y, voxel.z)] = voxel
        }
        revision = snapshot.revision
        sourceFrameId = snapshot.sourceFrameId
        return DeltaResult.Applied
    }

    fun values(): List<RemoteVoxel> = voxels.values.toList()
    /** Bounded UI cache. The revision map remains complete; only nearby cells are composed. */
    fun displayValues(position:FloatArray,voxelSize:Float,limit:Int=40_000):List<RemoteVoxel> {
        require(position.size==3&&voxelSize>0&&limit>0)
        if(voxels.size<=limit)return values()
        return voxels.values.asSequence().map{voxel->
            val dx=(voxel.x+.5f)*voxelSize-position[0];val dy=(voxel.y+.5f)*voxelSize-position[1];val dz=(voxel.z+.5f)*voxelSize-position[2]
            voxel to (dx*dx+dy*dy+dz*dz)
        }.sortedBy{it.second}.take(limit).map{it.first}.toList()
    }
    fun clear() { voxels.clear(); sessionId=null; revision=0; sourceFrameId=0 }
}
