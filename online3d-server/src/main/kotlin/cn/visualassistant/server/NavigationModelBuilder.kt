package cn.visualassistant.server

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.math.ceil

data class NavigationNode(val id:Int,val x:Int,val floorY:Int,val z:Int)
data class NavigationEdge(val from:Int,val to:Int)
data class NavigationModel(val voxelSize:Float,val sourceRevision:Long,val bodyRadiusMeters:Float,val bodyHeightMeters:Float,val nodes:List<NavigationNode>,val edges:List<NavigationEdge>)

/** Conservative first pass: unknown body-volume evidence always rejects a node. */
object NavigationModelBuilder{
    fun build(map:SparseVoxelMap,bodyRadiusMeters:Float=.15f,bodyHeightMeters:Float=1.80f):NavigationModel{
        val cells=map.snapshot();val radius=ceil(bodyRadiusMeters/map.voxelSize).toInt()
        val heights=listOf(.20f,.65f,1.10f,1.55f,bodyHeightMeters).map{ceil(it/map.voxelSize).toInt()}.distinct()
        val candidates=cells.asSequence().filter{it.value.state()==VoxelState.RELIABLE_OCCUPIED}.map{it.key}.filter{floor->
            heights.all{dy->(-radius..radius).all{dx->(-radius..radius).all{dz->
                if(dx*dx+dz*dz>radius*radius)true else cells[VoxelKey(floor.x+dx,floor.y+dy,floor.z+dz)]?.state()==VoxelState.RELIABLE_FREE
            }}}
        }.sortedWith(compareBy<VoxelKey>{it.x}.thenBy{it.z}.thenBy{it.y}).toList()
        val nodes=candidates.mapIndexed{i,k->NavigationNode(i,k.x,k.y,k.z)};val byColumn=nodes.associateBy{it.x to it.z};val edges=ArrayList<NavigationEdge>()
        nodes.forEach{node->listOf(1 to 0,0 to 1).forEach{(dx,dz)->byColumn[node.x+dx to node.z+dz]?.let{other->if(kotlin.math.abs(other.floorY-node.floorY)*map.voxelSize<=.10f)edges+=NavigationEdge(node.id,other.id)}}}
        return NavigationModel(map.voxelSize,map.revision,bodyRadiusMeters,bodyHeightMeters,nodes,edges)
    }
    fun write(model:NavigationModel,path:Path){Files.createDirectories(path.parent);val temp=path.resolveSibling(path.fileName.toString()+".tmp")
        val nodes=model.nodes.joinToString(","){"{\"id\":${it.id},\"x\":${it.x},\"floorY\":${it.floorY},\"z\":${it.z}}"};val edges=model.edges.joinToString(","){"[${it.from},${it.to}]"}
        val reason=if(model.nodes.isEmpty())"NO_FULL_BODY_CLEARANCE" else "PROTOTYPE_REQUIRES_VALIDATION"
        Files.writeString(temp,"{\"schemaVersion\":2,\"status\":\"$reason\",\"safeForNavigation\":false,\"voxelSize\":${model.voxelSize},\"sourceRevision\":${model.sourceRevision},\"bodyRadiusMeters\":${model.bodyRadiusMeters},\"bodyHeightMeters\":${model.bodyHeightMeters},\"nodes\":[$nodes],\"edges\":[$edges]}")
        Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE)
    }
}
