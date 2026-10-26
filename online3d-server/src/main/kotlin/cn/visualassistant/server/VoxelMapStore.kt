package cn.visualassistant.server

import java.io.*
import java.nio.file.*
import java.util.zip.CRC32

data class MapLoadResult(val source: Path, val recoveredFromBackup: Boolean, val cells: Int)

class VoxelMapStore(private val path: Path) {
    private val backup get()=path.resolveSibling(path.fileName.toString()+".bak")
    private val temporary get()=path.resolveSibling(path.fileName.toString()+".tmp")

    fun save(map:SparseVoxelMap) {
        Files.createDirectories(path.toAbsolutePath().parent)
        val payload=ByteArrayOutputStream()
        DataOutputStream(payload).use { out ->
            out.writeInt(MAGIC);out.writeInt(VERSION);out.writeFloat(map.voxelSize)
            out.writeLong(map.revision);out.writeLong(map.lastDepthTimestampNs)
            val items=map.snapshot().toSortedMap(compareBy<VoxelKey>{it.x}.thenBy{it.y}.thenBy{it.z})
            out.writeInt(items.size)
            items.forEach { (k,e) ->
                out.writeInt(k.x);out.writeInt(k.y);out.writeInt(k.z);out.writeInt(e.score)
                out.writeInt(e.occupiedObservations);out.writeInt(e.freeObservations)
                out.writeLong(e.confidenceSum);out.writeLong(e.confidenceSquareSum);out.writeLong(e.viewMask)
                out.writeLong(e.lastFrameId);out.writeLong(e.lastOccupiedFrameId);out.writeLong(e.lastFreeFrameId);out.writeLong(e.lastSeenNs)
            }
        }
        val bytes=payload.toByteArray();val crc=CRC32().apply{update(bytes)}.value
        DataOutputStream(BufferedOutputStream(Files.newOutputStream(temporary,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING))).use{out->out.write(bytes);out.writeLong(crc)}
        if(Files.exists(path)) Files.copy(path,backup,StandardCopyOption.REPLACE_EXISTING)
        try { Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING) }
        catch(_:AtomicMoveNotSupportedException){Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING)}
    }

    fun loadInto(map:SparseVoxelMap):MapLoadResult {
        val primary=runCatching{read(path,map)}
        if(primary.isSuccess)return MapLoadResult(path,false,primary.getOrThrow())
        val recovered=runCatching{read(backup,map)}
        if(recovered.isSuccess)return MapLoadResult(backup,true,recovered.getOrThrow())
        throw IOException("home map and backup are unreadable",primary.exceptionOrNull())
    }

    private fun read(source:Path,map:SparseVoxelMap):Int {
        val all=Files.readAllBytes(source);require(all.size>=MIN_BYTES){"map file too short"}
        val payload=all.copyOf(all.size-8);val expected=DataInputStream(ByteArrayInputStream(all,all.size-8,8)).readLong()
        val actual=CRC32().apply{update(payload)}.value;require(actual==expected){"map checksum mismatch"}
        DataInputStream(ByteArrayInputStream(payload)).use{input->
            require(input.readInt()==MAGIC);require(input.readInt()==VERSION)
            val size=input.readFloat();require(kotlin.math.abs(size-map.voxelSize)<.0001f){"voxel size mismatch"}
            val revision=input.readLong();val timestamp=input.readLong();val count=input.readInt();require(count in 0..MAX_CELLS)
            val cells=HashMap<VoxelKey,VoxelEvidence>(count)
            repeat(count){
                val key=VoxelKey(input.readInt(),input.readInt(),input.readInt())
                val e=VoxelEvidence(input.readInt(),input.readInt(),input.readInt(),input.readLong(),input.readLong(),input.readLong(),input.readLong(),input.readLong(),input.readLong(),input.readLong())
                require(e.score in -12..12);require(e.occupiedObservations>=0&&e.freeObservations>=0)
                require(cells.put(key,e)==null){"duplicate voxel"}
            }
            require(input.available()==0){"trailing map payload"};map.restore(cells,revision,timestamp);return count
        }
    }
    companion object { private const val MAGIC=0x56414d50;private const val VERSION=1;private const val MAX_CELLS=2_000_000;private const val MIN_BYTES=40 }
}
