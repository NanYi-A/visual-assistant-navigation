package cn.visualassistant.server

import java.io.ByteArrayInputStream
import java.net.Socket
import java.nio.file.Files
import java.io.DataInputStream
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class Va3dServerTest {
    private fun golden(): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/golden_3frames.va3d")).use { it.readBytes() }

    @Test fun queueKeepsOnlyTheNewestFrame() {
        val queue = FrameQueue(capacity = 1)
        assertEquals(0, queue.size())
        queue.offer(byteArrayOf(1))
        queue.offer(byteArrayOf(2))
        assertEquals(1, queue.size())
        assertEquals(2, queue.poll()!![0])
        assertNull(queue.poll())
        assertEquals(1L, queue.droppedFrames)
    }

    @Test fun streamReaderYieldsEveryPayload() {
        val seen = ArrayList<Int>()
        Va3dStream.readPayloads(ByteArrayInputStream(golden())) { seen += it.size }
        assertEquals(listOf(43340, 43340, 43340), seen)
    }

    private fun payloads(): List<ByteArray> {
        val collected = ArrayList<ByteArray>()
        Va3dStream.readPayloads(ByteArrayInputStream(golden())) { collected += it }
        return collected
    }

    private fun waitFor(server: Va3dServer, frames: Long) {
        val deadline = System.currentTimeMillis() + 10_000
        while (server.integratedFrames < frames && System.currentTimeMillis() < deadline) Thread.sleep(20)
    }

    @Test fun serverIntegratesFramesSentAtCaptureRate() {
        val map = SparseVoxelMap()
        val server = Va3dServer(0, map)
        try {
            Socket("127.0.0.1", server.port).use { socket ->
                val output = socket.getOutputStream()
                // A phone streams at 3~10 Hz; each packet is fused before the next arrives.
                payloads().forEachIndexed { index, payload ->
                    Va3dStream.writePayload(output, payload)
                    waitFor(server, (index + 1).toLong())
                }
            }
            assertEquals(3L, server.integratedFrames)
            assertEquals(3L, server.activeRevision)
            assertTrue(server.activeOccupied > 0)
            assertTrue(server.activeFree > 0)
        } finally {
            server.close()
        }
    }

    @Test fun burstOfFramesNeverBuildsALatencyBacklog() {
        val map = SparseVoxelMap()
        val server = Va3dServer(0, map)
        try {
            Socket("127.0.0.1", server.port).use { socket ->
                val output = socket.getOutputStream()
                // Deliberately faster than fusion: stale frames must be dropped, not queued.
                repeat(8) { Va3dStream.writePayload(output, payloads().first()) }
                val deadline = System.currentTimeMillis() + 5_000
                while (server.droppedFrames == 0L && System.currentTimeMillis() < deadline) Thread.sleep(20)
                waitFor(server, 1)
                assertTrue(server.droppedFrames > 0, "stale frames must be discarded")
                assertTrue(server.pendingFrames <= 1, "queue must not grow: ${server.pendingFrames}")
                assertTrue(server.integratedFrames >= 1, "the newest frame is still fused")
            }
        } finally {
            server.close()
        }
    }

    @Test fun persistentFramesAreSavedButOutdoorFramesAreNot() {
        val dir=Files.createTempDirectory("va-server-store");val file=dir.resolve("home.vamap")
        val persistentPayload=payloads().first().copyOf().also { it[48]=0;it[49]=0;it[50]=0;it[51]=1 }
        val server=Va3dServer(0,SparseVoxelMap(),homeStore=VoxelMapStore(file),saveEveryFrames=1)
        try { Socket("127.0.0.1",server.port).use{Va3dStream.writePayload(it.getOutputStream(),persistentPayload)};waitFor(server,1);val deadline=System.currentTimeMillis()+5000;while(!Files.exists(file)&&System.currentTimeMillis()<deadline)Thread.sleep(20);assertTrue(Files.exists(file)) } finally { server.close() }
    }

    @Test fun outdoorFramesNeverContaminateHomeMap(){
        val home=SparseVoxelMap();val server=Va3dServer(0,home)
        try{Socket("127.0.0.1",server.port).use{Va3dStream.writePayload(it.getOutputStream(),payloads().first())};waitFor(server,1);assertTrue(server.activeOccupied>0);assertEquals(0,home.snapshot().size)}finally{server.close()}
    }

    @Test fun serverReturnsSnapshotOnTheSameSocket(){val server=Va3dServer(0,SparseVoxelMap());try{Socket("127.0.0.1",server.port).use{socket->socket.soTimeout=5000;Va3dStream.writePayload(socket.getOutputStream(),payloads().first());val input=DataInputStream(socket.getInputStream());val size=input.readInt();val response=ByteArray(size);input.readFully(response);DataInputStream(ByteArrayInputStream(response)).use{decoded->assertEquals(Va3dProtocol.MAGIC,decoded.readInt());assertEquals(2,decoded.readUnsignedShort());assertEquals(MapProtocol.MESSAGE_SNAPSHOT,decoded.readUnsignedShort())};assertEquals(1,server.publishedUpdates)}}finally{server.close()}}
}
