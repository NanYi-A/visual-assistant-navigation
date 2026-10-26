package cn.visualassistant.server

import java.nio.file.Files
import java.nio.file.Path

fun main(args: Array<String>) {
    val flag = args.indexOf("--port")
    val port = if (flag >= 0 && flag + 1 < args.size) args[flag + 1].toInt() else 38400
    val voxelCm = args.indexOf("--voxel-cm").let { if (it >= 0 && it + 1 < args.size) args[it + 1].toInt() else 8 }
    val mapPath = args.indexOf("--home-map").let { if(it>=0&&it+1<args.size) Path.of(args[it+1]) else Path.of("maps","home.vamap") }
    val modelsRoot = args.indexOf("--models").let { if(it>=0&&it+1<args.size) Path.of(args[it+1]) else Path.of("models") }
    val map = SparseVoxelMap(voxelSize = voxelCm / 100f)
    val store=VoxelMapStore(mapPath)
    if(Files.exists(mapPath)) runCatching{store.loadInto(map)}.onSuccess{println("Loaded home map ${it.cells} cells revision=${map.revision}${if(it.recoveredFromBackup) " from backup" else ""}")}.onFailure{println("Home map not loaded: ${it.message}")}
    val server = Va3dServer(port, map, homeStore=store,modelsRoot=modelsRoot)
    println("VA3D server listening on ${server.port}; voxel=${voxelCm}cm")
    Runtime.getRuntime().addShutdownHook(Thread { server.close() })
    while (true) {
        Thread.sleep(5000)
        println(
            "frames=${server.integratedFrames} dropped=${server.droppedFrames} " +
                "revision=${server.activeRevision} occupied=${server.activeOccupied} free=${server.activeFree}"
        )
    }
}
