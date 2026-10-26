package cn.visualassistant.app.spatial

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.*
import java.util.concurrent.Executors
import java.util.UUID
import cn.visualassistant.app.online3d.*
import cn.visualassistant.app.home.HomeGeoAnchor

data class HomeModelEntry(val id:String,val name:String,val createdAt:Long,val status:String,val hasScanModel:Boolean=false,val hasCognitionModel:Boolean=false,val parentModelId:String?=null,val segmentIndex:Int=0,val relocalizationStatus:String="NOT_REQUIRED",val hasOptimizedModel:Boolean=false,val optimizedStatus:String="尚未同步",val navigationReady:Boolean=false,val homeMapCells:Int=0){
    val displayTime:String get()=SimpleDateFormat("MM月dd日 HH:mm",Locale.CHINA).format(Date(createdAt))
    /** A map with no cells is not navigable, whatever the mesh pipeline claimed. */
    val hasHomeMap:Boolean get()=homeMapCells>0
}
data class HomePlace(val id:String,val name:String,val createdAt:Long,val x:Float,val y:Float,val z:Float,val forwardX:Float,val forwardY:Float,val forwardZ:Float,val userConfirmed:Boolean=true)

class HomeModelLibrary(context:Context){
    private val prefs=context.getSharedPreferences("home_model_library",Context.MODE_PRIVATE)
    private val root=File(context.filesDir,"home_models").apply{mkdirs()}
    private val writer=Executors.newSingleThreadExecutor()
    fun load():List<HomeModelEntry>{
        val source=prefs.getString("models","[]")?:"[]"
        return runCatching{val array=JSONArray(source);List(array.length()){i->val item=array.getJSONObject(i);HomeModelEntry(item.getString("id"),item.getString("name"),item.getLong("createdAt"),item.getString("status"),item.optBoolean("hasScanModel",false),item.optBoolean("hasCognitionModel",false),item.optString("parentModelId").takeIf{it.isNotBlank()},item.optInt("segmentIndex",0),item.optString("relocalizationStatus","NOT_REQUIRED"),item.optBoolean("hasOptimizedModel",false),item.optString("optimizedStatus","尚未同步"),item.optBoolean("navigationReady",false),item.optInt("homeMapCells",0))}}.getOrDefault(emptyList()).sortedByDescending{it.createdAt}
    }
    fun create(id:String):HomeModelEntry{
        val now=System.currentTimeMillis();val entry=HomeModelEntry(id,"家庭模型 ${SimpleDateFormat("MM-dd HH:mm",Locale.CHINA).format(Date(now))}",now,"正在扫描")
        save(listOf(entry)+load().filterNot{it.id==id});return entry
    }
    fun createSegment(id:String,parentId:String):HomeModelEntry{
        val all=load();val parent=all.firstOrNull{it.id==parentId}?:error("parent model missing")
        val rootId=parent.parentModelId?:parent.id
        val next=all.count{it.parentModelId==rootId}+1;val now=System.currentTimeMillis()
        val entry=HomeModelEntry(id,"${parent.name.substringBefore(" · 补扫")} · 补扫 $next",now,"补扫中；与主模型隔离",parentModelId=rootId,segmentIndex=next,relocalizationStatus="WAITING_RELOCALIZATION")
        save(listOf(entry)+all.filterNot{it.id==id});return entry
    }
    fun updateStatus(id:String,status:String){save(load().map{if(it.id==id)it.copy(status=status)else it})}
    fun saveGeoAnchor(modelId:String,anchor:HomeGeoAnchor){
        val objectValue=JSONObject().put("latitude",anchor.latitude).put("longitude",anchor.longitude)
            .put("accuracyMeters",anchor.accuracyMeters.toDouble()).put("address",anchor.address).put("timeMs",anchor.timeMs)
        prefs.edit().putString("geo_$modelId",objectValue.toString()).apply()
    }
    fun loadGeoAnchor(modelId:String):HomeGeoAnchor?=runCatching{
        val item=JSONObject(prefs.getString("geo_$modelId",null)?:return@runCatching null)
        HomeGeoAnchor(item.getDouble("latitude"),item.getDouble("longitude"),item.getDouble("accuracyMeters").toFloat(),item.optString("address"),item.optLong("timeMs"))
    }.getOrNull()
    fun markInterruptedScans(){save(load().map{if(it.status=="正在扫描")it.copy(status="扫描意外中断，已有手机检查点保留")else it})}
    fun saveScanMesh(id:String,mesh:RemoteColoredMesh,onSaved:()->Unit={}){
        if(mesh.revision%24L!=0L)return
        writer.execute{runCatching{val folder=File(root,id).apply{mkdirs()};val temp=File(folder,"scan.tmp.bin");DataOutputStream(BufferedOutputStream(FileOutputStream(temp))).use{out->out.writeLong(mesh.sessionId);out.writeLong(mesh.revision);out.writeLong(mesh.sourceFrameId);out.writeFloat(mesh.voxelSize);out.writeInt(mesh.triangles.size);mesh.triangles.forEach{t->listOf(t.a,t.b,t.c,t.normal).forEach{p->out.writeFloat(p.x);out.writeFloat(p.y);out.writeFloat(p.z)};listOf(t.ar,t.ag,t.ab,t.br,t.bg,t.bb,t.cr,t.cg,t.cb).forEach(out::writeByte)}};val destination=File(folder,"scan.bin");if(destination.exists())destination.delete();require(temp.renameTo(destination));save(load().map{if(it.id==id)it.copy(status="扫描模型可查看，通行认知处理中",hasScanModel=true)else it})}.onSuccess{onSaved()}}
    }
    fun loadScanMesh(id:String):RemoteColoredMesh?=runCatching{
        readMesh(File(File(root,id),"scan.bin"))
    }.getOrNull()
    private fun readMesh(file:File):RemoteColoredMesh=
        DataInputStream(BufferedInputStream(FileInputStream(file))).use{input->
            val session=input.readLong()
            val revision=input.readLong()
            val source=input.readLong()
            val voxel=input.readFloat()
            val count=input.readInt()
            require(count in 1..50_000)
            fun readPoint()=RemotePoint3(input.readFloat(),input.readFloat(),input.readFloat())
            val triangles=List(count){
                RemoteColoredTriangle(
                    readPoint(),readPoint(),readPoint(),readPoint(),
                    input.readUnsignedByte(),input.readUnsignedByte(),input.readUnsignedByte(),
                    input.readUnsignedByte(),input.readUnsignedByte(),input.readUnsignedByte(),
                    input.readUnsignedByte(),input.readUnsignedByte(),input.readUnsignedByte()
                )
            }
            require(input.read()==-1)
            RemoteColoredMesh(session,revision,source,voxel,triangles)
        }
    fun saveOptimizedMesh(id:String,mesh:RemoteColoredMesh,status:String,navigationReady:Boolean,onSaved:()->Unit={}){writer.execute{runCatching{
        val folder=File(root,id).apply{mkdirs()};val temp=File(folder,"optimized.tmp.bin")
        DataOutputStream(BufferedOutputStream(FileOutputStream(temp))).use{out->out.writeLong(mesh.sessionId);out.writeLong(mesh.revision);out.writeLong(mesh.sourceFrameId);out.writeFloat(mesh.voxelSize);out.writeInt(mesh.triangles.size);mesh.triangles.forEach{t->listOf(t.a,t.b,t.c,t.normal).forEach{p->out.writeFloat(p.x);out.writeFloat(p.y);out.writeFloat(p.z)};listOf(t.ar,t.ag,t.ab,t.br,t.bg,t.bb,t.cr,t.cg,t.cb).forEach(out::writeByte)}}
        val destination=File(folder,"optimized.bin");if(destination.exists())destination.delete();require(temp.renameTo(destination));save(load().map{if(it.id==id)it.copy(hasOptimizedModel=true,optimizedStatus=status,navigationReady=navigationReady)else it})}.onSuccess{onSaved()}}}
    fun loadOptimizedMesh(id:String):RemoteColoredMesh?=runCatching{readMesh(File(File(root,id),"optimized.bin"))}.getOrNull()
    fun updateOptimizedStatus(id:String,status:String,navigationReady:Boolean){save(load().map{if(it.id==id)it.copy(optimizedStatus=status,navigationReady=navigationReady)else it})}
    fun rejectOptimizedMesh(id:String,status:String){File(File(root,id),"optimized.bin").delete();save(load().map{if(it.id==id)it.copy(hasOptimizedModel=false,optimizedStatus=status,navigationReady=false)else it})}
    fun saveCognition(id:String,voxels:List<RemoteVoxel>,voxelSize:Float,revision:Long,onSaved:()->Unit={}){
        if(revision%24L!=0L||voxels.isEmpty())return
        writer.execute{runCatching{
            val folder=File(root,id).apply{mkdirs()};val temp=File(folder,"cognition.tmp.bin")
            DataOutputStream(BufferedOutputStream(FileOutputStream(temp))).use{out->
                out.writeFloat(voxelSize);out.writeLong(revision);out.writeInt(voxels.size)
                voxels.forEach{v->out.writeInt(v.x);out.writeInt(v.y);out.writeInt(v.z);out.writeByte(v.state.ordinal);out.writeByte(v.confidence);out.writeInt(v.observations);out.writeLong(v.updatedAtMs);out.writeByte(v.surfaceMask)}
            }
            val destination=File(folder,"cognition.bin");if(destination.exists())destination.delete();require(temp.renameTo(destination))
            save(load().map{if(it.id==id)it.copy(status=if(it.hasScanModel)"扫描模型与碰撞认知可查看" else "碰撞认知可查看，扫描模型处理中",hasCognitionModel=true)else it})
        }.onSuccess{onSaved()}}
    }
    fun loadCognition(id:String):SavedCognition?=runCatching{
        DataInputStream(BufferedInputStream(FileInputStream(File(File(root,id),"cognition.bin")))).use{input->
            val size=input.readFloat();val revision=input.readLong();val count=input.readInt();require(size>0f&&count in 1..150_000)
            val voxels=List(count){RemoteVoxel(input.readInt(),input.readInt(),input.readInt(),RemoteVoxelState.entries[input.readUnsignedByte()],input.readUnsignedByte(),input.readInt(),input.readLong(),input.readUnsignedByte())}
            require(input.read()==-1);SavedCognition(size,revision,voxels)
        }
    }.getOrNull()

    // ---- persistent home map (semi-automatic navigation) ----

    /**
     * Home maps live in the same model folder as the named places, so a map and the destinations
     * it contains can never drift apart. Writing goes through [HomeMapStore], which is atomic and
     * keeps a backup; a truncated file costs the newest session, not the whole map.
     */
    fun homeMapStore(modelId:String):HomeMapStore = HomeMapStore(File(File(root,modelId),"nav"))

    /**
     * Loads the stored grid and keeps its anchors in step with the editable place list.
     *
     * places.json is the source of truth for named destinations; the binary home map only carries
     * a cached copy. Syncing here means a place renamed or deleted in the library is immediately
     * reflected in navigation, and a legacy map that was written before anchors were persisted
     * still works as long as its places.json exists.
     */
    fun loadHomeMap(modelId:String):HomeMap? {
        val map=homeMapStore(modelId).load() ?: return null
        syncAnchorsInto(map, modelId)
        return map
    }
    fun saveHomeMap(modelId:String,map:HomeMap):Boolean = homeMapStore(modelId).save(map)
    fun hasHomeMap(modelId:String):Boolean = homeMapStore(modelId).exists()

    /** Keeps the map's anchors in step with the place list the UI already edits. */
    fun syncHomeMapAnchors(modelId:String):HomeMap? = loadHomeMap(modelId)

    private fun syncAnchorsInto(map:HomeMap, modelId:String){
        val places=loadPlaces(modelId)
        val placeIds=places.map{it.id}.toSet()
        // A place the user renamed or deleted in the library must not leave a stale anchor behind,
        // or "take me to the bathroom" would keep aiming at a name that no longer exists.
        map.anchorList.filter{it.id !in placeIds}.forEach{map.removeAnchor(it.id)}
        places.forEach{place->
            val existing=map.anchorList.firstOrNull{it.id==place.id}
            val fresh=HomeMapAnchor(place.id,place.name,place.createdAt,place.x,place.y,place.z,
                place.forwardX,place.forwardY,place.forwardZ)
            if(existing==null || existing.name!=place.name ||
                existing.x!=place.x || existing.y!=place.y || existing.z!=place.z ||
                existing.forwardX!=place.forwardX || existing.forwardY!=place.forwardY || existing.forwardZ!=place.forwardZ){
                map.removeAnchor(place.id)
                map.addAnchor(fresh)
            }
        }
    }

    /** Records how much ground the map covers, for the library list. */
    fun setHomeMapCells(modelId:String,cells:Int){
        save(load().map{if(it.id==modelId)it.copy(homeMapCells=cells)else it})
    }
    /** Marks the map unusable after a failed write so the UI stops advertising navigation. */
    fun clearHomeMap(modelId:String){
        setHomeMapCells(modelId,0)
        runCatching{ homeMapStore(modelId).delete() }
    }
    fun delete(id:String):Boolean{
        val folder=File(root,id)
        val safe=runCatching{folder.canonicalFile.parentFile==root.canonicalFile}.getOrDefault(false)
        if(!safe)return false
        val deleted=!folder.exists()||folder.deleteRecursively()
        if(deleted)save(load().filterNot{it.id==id})
        if(deleted)prefs.edit().remove("geo_$id").apply()
        return deleted
    }
    fun loadPlaces(modelId:String):List<HomePlace> = runCatching{
        val file=File(File(root,modelId),"places.json");if(!file.isFile)return@runCatching emptyList()
        val array=JSONArray(file.readText());List(array.length()){index->array.getJSONObject(index).let{item->HomePlace(item.getString("id"),item.getString("name"),item.getLong("createdAt"),item.getDouble("x").toFloat(),item.getDouble("y").toFloat(),item.getDouble("z").toFloat(),item.getDouble("forwardX").toFloat(),item.getDouble("forwardY").toFloat(),item.getDouble("forwardZ").toFloat(),item.optBoolean("userConfirmed",false))}}
    }.getOrDefault(emptyList()).sortedBy{it.createdAt}
    fun addPlace(modelId:String,name:String,pose:RemotePose3):HomePlace{
        val place=HomePlace(UUID.randomUUID().toString(),name.trim(),System.currentTimeMillis(),pose.x,pose.y,pose.z,pose.forwardX,pose.forwardY,pose.forwardZ,true)
        savePlaces(modelId,loadPlaces(modelId)+place);return place
    }
    fun renamePlace(modelId:String,placeId:String,name:String){savePlaces(modelId,loadPlaces(modelId).map{if(it.id==placeId)it.copy(name=name.trim())else it})}
    fun deletePlace(modelId:String,placeId:String){savePlaces(modelId,loadPlaces(modelId).filterNot{it.id==placeId})}
    private fun savePlaces(modelId:String,places:List<HomePlace>){
        val folder=File(root,modelId).apply{mkdirs()};val temp=File(folder,"places.tmp.json");val destination=File(folder,"places.json")
        val array=JSONArray();places.forEach{p->array.put(JSONObject().put("id",p.id).put("name",p.name).put("createdAt",p.createdAt).put("x",p.x).put("y",p.y).put("z",p.z).put("forwardX",p.forwardX).put("forwardY",p.forwardY).put("forwardZ",p.forwardZ).put("userConfirmed",p.userConfirmed))}
        temp.writeText(array.toString());if(destination.exists())destination.delete();require(temp.renameTo(destination))
    }
    private fun save(entries:List<HomeModelEntry>){val array=JSONArray();entries.forEach{array.put(JSONObject().put("id",it.id).put("name",it.name).put("createdAt",it.createdAt).put("status",it.status).put("hasScanModel",it.hasScanModel).put("hasCognitionModel",it.hasCognitionModel).put("parentModelId",it.parentModelId?:"").put("segmentIndex",it.segmentIndex).put("relocalizationStatus",it.relocalizationStatus).put("hasOptimizedModel",it.hasOptimizedModel).put("optimizedStatus",it.optimizedStatus).put("navigationReady",it.navigationReady).put("homeMapCells",it.homeMapCells))};prefs.edit().putString("models",array.toString()).apply()}
}

data class SavedCognition(val voxelSize:Float,val revision:Long,val voxels:List<RemoteVoxel>)
