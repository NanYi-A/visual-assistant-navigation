package cn.visualassistant.app.spatial

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Persistent home map: the confirmed world cells of one home, kept across sessions.
 *
 * Why this exists: `SessionGridMemory` already stores cells in ARCore world coordinates and
 * already solves "the map disappears as you walk closer", but it dies with the process. This
 * class only adds durability and cross-session alignment — it deliberately does not introduce a
 * second mapping algorithm, because the 2.5D grid has already been validated on a real phone.
 *
 * Three rules are absolute here:
 *  1. Unknown stays unknown. A cell that was never observed is absent, never inferred.
 *  2. Writing is atomic. A half-written map must never replace a good one.
 *  3. A damaged map falls back to the previous good copy instead of throwing.
 */

/** One 10 cm world cell. Height bounds are relative to the floor at the time it was observed. */
data class HomeMapCell(
    val indexX: Int,
    val indexZ: Int,
    val occupied: Boolean,
    val evidence: Int,
    val minHeight: Float? = null,
    val maxHeight: Float? = null,
) {
    val worldX: Float get() = (indexX + 0.5f) * HomeMap.CELL_SIZE
    val worldZ: Float get() = (indexZ + 0.5f) * HomeMap.CELL_SIZE
}

/** A named position the user confirmed by voice, used as a destination and as a session anchor. */
data class HomeMapAnchor(
    val id: String,
    val name: String,
    val createdAt: Long,
    val x: Float,
    val y: Float,
    val z: Float,
    val forwardX: Float,
    val forwardY: Float,
    val forwardZ: Float,
)

/**
 * A 2D rigid transform mapping stored map coordinates into the current ARCore world.
 *
 * `headingKnown` is the honest core of this class: one anchor can only recover translation,
 * never rotation. Rather than invent a facing direction we report the uncertainty and let the
 * navigation layer fall back to distance-only guidance.
 */
data class HomeMapAlignment(
    val translateX: Float,
    val translateZ: Float,
    val cosYaw: Float,
    val sinYaw: Float,
    val headingKnown: Boolean,
    val residualMeters: Float,
) {
    fun mapToWorldX(mapX: Float, mapZ: Float): Float = mapX * cosYaw - mapZ * sinYaw + translateX
    fun mapToWorldZ(mapX: Float, mapZ: Float): Float = mapX * sinYaw + mapZ * cosYaw + translateZ
    fun worldToMapX(worldX: Float, worldZ: Float): Float = (worldX - translateX) * cosYaw + (worldZ - translateZ) * sinYaw
    fun worldToMapZ(worldX: Float, worldZ: Float): Float = -(worldX - translateX) * sinYaw + (worldZ - translateZ) * cosYaw

    /**
     * Rotate a world *direction* into map coordinates.
     *
     * A direction has no position, so translation must not be applied to it. Feeding a direction
     * through [worldToMapX] would subtract an offset from something that never had one, and the
     * resulting heading — and therefore every turn instruction — would be wrong.
     */
    fun directionToMapX(dx: Float, dz: Float): Float = dx * cosYaw + dz * sinYaw
    fun directionToMapZ(dx: Float, dz: Float): Float = -dx * sinYaw + dz * cosYaw

    fun forwardX(): Float = -sinYaw
    fun forwardZ(): Float = -cosYaw

    companion object {
        /** No rotation known: map axes are assumed parallel to the world, flagged as uncertain. */
        fun translationOnly(dx: Float, dz: Float, residual: Float) =
            HomeMapAlignment(dx, dz, 1f, 0f, headingKnown = false, residualMeters = residual)
    }
}

class HomeMap(
    val cellSize: Float = CELL_SIZE,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    private val cells = HashMap<Long, HomeMapCell>()
    private val anchors = LinkedHashMap<String, HomeMapAnchor>()

    val cellCount: Int get() = cells.size
    val freeCount: Int get() = cells.values.count { !it.occupied }
    val occupiedCount: Int get() = cells.values.count { it.occupied }
    val anchorList: List<HomeMapAnchor> get() = anchors.values.sortedBy { it.createdAt }
    val isEmpty: Boolean get() = cells.isEmpty()

    fun cellAt(worldX: Float, worldZ: Float): HomeMapCell? = cells[key(indexOf(worldX), indexOf(worldZ))]
    fun cellAtIndex(indexX: Int, indexZ: Int): HomeMapCell? = cells[key(indexX, indexZ)]
    fun allCells(): List<HomeMapCell> = cells.values.toList()

    fun put(cell: HomeMapCell) {
        cells[key(cell.indexX, cell.indexZ)] = cell
    }

    /** The largest contiguous stretch of free cells around a point, used for width estimates. */
    fun freeWidthAt(worldX: Float, worldZ: Float, limit: Float = 1.2f): Float {
        val centreX = indexOf(worldX)
        val centreZ = indexOf(worldZ)
        if (cellAtIndex(centreX, centreZ)?.occupied != false) return 0f
        val maxCells = (limit / cellSize).toInt()
        var width = cellSize
        for (dx in -maxCells..maxCells) {
            if (dx == 0) continue
            if (cellAtIndex(centreX + dx, centreZ)?.occupied != false) break
            width += cellSize
        }
        return width.coerceAtMost(limit)
    }

    // ---- anchors ----

    fun addAnchor(anchor: HomeMapAnchor) { anchors[anchor.id] = anchor }
    fun anchor(id: String): HomeMapAnchor? = anchors[id]
    fun removeAnchor(id: String): Boolean = anchors.remove(id) != null
    fun renameAnchor(id: String, name: String): Boolean {
        val old = anchors[id] ?: return false
        anchors[id] = old.copy(name = name.trim()); return true
    }

    /** Exact name match first, then a unique substring match. Never guesses among several hits. */
    fun findAnchorByName(query: String): List<HomeMapAnchor> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        val exact = anchorList.filter { it.name == needle }
        if (exact.isNotEmpty()) return exact
        return anchorList.filter { needle in it.name || it.name in needle }
    }

    // ---- merging a live session ----

    /**
     * Merge cells observed in the current session.
     *
     * An observed obstacle always wins. An old obstacle is only cleared after
     * [freeConfirmationsToClear] consecutive free observations, matching the live rule in
     * `SessionGridMemory` — loosening it here would make the stored map claim a space is open
     * that nobody has actually seen open.
     */
    fun mergeObserved(observed: List<TraversabilityCell>, freeConfirmationsToClear: Int = FREE_CONFIRMATIONS_TO_CLEAR) {
        observed.filter { !it.inflated && it.worldX != null && it.worldZ != null }.forEach { cell ->
            val indexX = indexOf(cell.worldX!!)
            val indexZ = indexOf(cell.worldZ!!)
            val old = cells[key(indexX, indexZ)]
            val minHeight = cell.minHeight?.takeIf { it.isFinite() }
            val maxHeight = cell.maxHeight?.takeIf { it.isFinite() }
            when {
                old == null -> cells[key(indexX, indexZ)] =
                    HomeMapCell(indexX, indexZ, cell.state == TraversabilityState.OCCUPIED,
                        cell.evidence, minHeight, maxHeight)
                cell.state == TraversabilityState.OCCUPIED -> {
                    // Seeing the obstacle again must restart the clearing countdown, otherwise a
                    // chair that was glimpsed once clears after six free views that never saw it.
                    clearConfirmations.remove(key(indexX, indexZ))
                    cells[key(indexX, indexZ)] =
                        HomeMapCell(indexX, indexZ, true, maxOf(old.evidence, cell.evidence),
                            minHeight ?: old.minHeight, maxHeight ?: old.maxHeight)
                }
                old.occupied -> {
                    val confirmations = (clearConfirmations[key(indexX, indexZ)] ?: 0) + 1
                    if (confirmations >= freeConfirmationsToClear) {
                        clearConfirmations.remove(key(indexX, indexZ))
                        cells[key(indexX, indexZ)] =
                            HomeMapCell(indexX, indexZ, false, cell.evidence)
                    } else {
                        clearConfirmations[key(indexX, indexZ)] = confirmations
                    }
                }
                else -> cells[key(indexX, indexZ)] =
                    old.copy(evidence = maxOf(old.evidence, cell.evidence))
            }
        }
    }

    private val clearConfirmations = HashMap<Long, Int>()

    /**
     * Rebuild the map into the current session's world frame so live cells and stored cells agree.
     *
     * After this call, [mapToWorldX] and friends become the identity, because stored coordinates
     * have been rewritten in place. The alignment itself is kept for reporting.
     *
     * The navigation session deliberately does *not* use this: rewriting the stored map into a
     * session-specific frame would make later sessions compound their alignment on top of it and
     * would leave the stored coordinates meaningless for merging another walk into the map.
     * Instead the session keeps this map canonical and converts live positions with
     * [HomeMapAlignment.worldToMapX]. This overload remains for callers that truly want to bake a
     * frame in, such as exporting the map for inspection.
     */
    fun applyAlignmentInPlace(alignment: HomeMapAlignment) {
        if (alignment.cosYaw == 1f && alignment.sinYaw == 0f && alignment.translateX == 0f && alignment.translateZ == 0f) return
        val rotated = cells.values.map { cell ->
            val nx = alignment.mapToWorldX(cell.worldX, cell.worldZ)
            val nz = alignment.mapToWorldZ(cell.worldX, cell.worldZ)
            cell.copy(indexX = indexOf(nx), indexZ = indexOf(nz))
        }
        cells.clear(); rotated.forEach { put(it) }
        val moved = anchors.values.map { anchor ->
            anchor.copy(
                x = alignment.mapToWorldX(anchor.x, anchor.z),
                z = alignment.mapToWorldZ(anchor.x, anchor.z),
                forwardX = alignment.mapToWorldX(anchor.forwardX, anchor.forwardZ),
                forwardZ = alignment.mapToWorldZ(anchor.forwardX, anchor.forwardZ),
            )
        }
        anchors.clear(); moved.forEach { addAnchor(it) }
        clearConfirmations.clear()
    }

    /** Copy anchored cells so the map can be stored in its own frame. */
    fun copy(): HomeMap {
        val other = HomeMap(cellSize, createdAt, updatedAt)
        cells.forEach { (k, v) -> other.cells[k] = v }
        anchors.forEach { (k, v) -> other.anchors[k] = v }
        return other
    }

    fun indexOf(value: Float): Int = kotlin.math.floor(value / cellSize).toInt()
    private fun key(indexX: Int, indexZ: Int) = (indexX.toLong() shl 32) xor (indexZ.toLong() and 0xffffffffL)

    // ---- serialisation ----

    fun encode(): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeInt(MAGIC); out.writeInt(FORMAT_VERSION); out.writeFloat(cellSize)
            out.writeLong(createdAt); out.writeLong(updatedAt)
            out.writeInt(cells.size)
            cells.values.forEach { cell ->
                out.writeInt(cell.indexX); out.writeInt(cell.indexZ)
                out.writeBoolean(cell.occupied); out.writeInt(cell.evidence)
                out.writeBoolean(cell.minHeight != null); if (cell.minHeight != null) out.writeFloat(cell.minHeight)
                out.writeBoolean(cell.maxHeight != null); if (cell.maxHeight != null) out.writeFloat(cell.maxHeight)
            }
            out.writeInt(anchors.size)
            anchors.values.forEach { anchor ->
                out.writeUTF(anchor.id); out.writeUTF(anchor.name); out.writeLong(anchor.createdAt)
                out.writeFloat(anchor.x); out.writeFloat(anchor.y); out.writeFloat(anchor.z)
                out.writeFloat(anchor.forwardX); out.writeFloat(anchor.forwardY); out.writeFloat(anchor.forwardZ)
            }
            out.flush()
        }
        return buffer.toByteArray()
    }

    companion object {
        const val CELL_SIZE = 0.1f
        const val FREE_CONFIRMATIONS_TO_CLEAR = 6
        private const val MAGIC = 0x484D4150 // "HMAP"
        private const val FORMAT_VERSION = 1
        private const val MAX_CELLS = 4_000_000

        fun decode(bytes: ByteArray): HomeMap {
            DataInputStream(bytes.inputStream()).use { input ->
                require(input.readInt() == MAGIC) { "not a home map file" }
                val version = input.readInt()
                require(version == FORMAT_VERSION) { "unsupported home map version $version" }
                val cellSize = input.readFloat()
                require(cellSize > 0f && cellSize <= 1f) { "invalid cell size" }
                val map = HomeMap(cellSize, input.readLong(), input.readLong())
                val cellCount = input.readInt()
                require(cellCount in 0..MAX_CELLS) { "invalid cell count" }
                repeat(cellCount) {
                    val indexX = input.readInt(); val indexZ = input.readInt()
                    val occupied = input.readBoolean(); val evidence = input.readInt()
                    val minHeight = if (input.readBoolean()) input.readFloat() else null
                    val maxHeight = if (input.readBoolean()) input.readFloat() else null
                    map.put(HomeMapCell(indexX, indexZ, occupied, evidence, minHeight, maxHeight))
                }
                val anchorCount = input.readInt()
                require(anchorCount in 0..10_000) { "invalid anchor count" }
                repeat(anchorCount) {
                    val id = input.readUTF(); val name = input.readUTF(); val createdAt = input.readLong()
                    map.addAnchor(HomeMapAnchor(id, name, createdAt, input.readFloat(), input.readFloat(),
                        input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat()))
                }
                return map
            }
        }
    }
}

/**
 * File-backed storage for a home map.
 *
 * Writes go to a temporary file and are renamed into place, and the previous good copy is kept
 * as a backup. A truncated or corrupt file therefore costs at most the newest session, never the
 * whole map.
 */
class HomeMapStore(private val folder: File) {
    init { folder.mkdirs() }

    private val mapFile get() = File(folder, FILE_NAME)
    private val backupFile get() = File(folder, "$FILE_NAME.bak")
    private val tempFile get() = File(folder, "$FILE_NAME.tmp")

    fun exists(): Boolean = mapFile.isFile || backupFile.isFile

    fun save(map: HomeMap): Boolean = runCatching {
        tempFile.outputStream().use { it.write(map.encode()) }
        if (mapFile.isFile) {
            backupFile.delete()
            if (!mapFile.renameTo(backupFile)) mapFile.copyTo(backupFile, overwrite = true)
        }
        if (!tempFile.renameTo(mapFile)) {
            tempFile.copyTo(mapFile, overwrite = true)
            tempFile.delete()
        }
        true
    }.getOrDefault(false)

    /** Returns the newest readable copy, preferring the primary file. Never throws. */
    fun load(): HomeMap? {
        loadFrom(mapFile)?.let { return it }
        return loadFrom(backupFile)
    }

    fun loadWithSource(): Pair<HomeMap, String>? {
        loadFrom(mapFile)?.let { return it to "primary" }
        loadFrom(backupFile)?.let { return it to "backup" }
        return null
    }

    private fun loadFrom(file: File): HomeMap? {
        if (!file.isFile || file.length() < MIN_BYTES) return null
        return runCatching { HomeMap.decode(file.readBytes()) }
            .getOrNull()
            ?.takeIf { it.cellSize > 0f }
    }

    fun delete(): Boolean {
        mapFile.delete(); backupFile.delete(); tempFile.delete(); return true
    }

    companion object {
        const val FILE_NAME = "home_map.bin"
        private const val MIN_BYTES = 24L

        fun newAnchor(name: String, x: Float, y: Float, z: Float,
                       forwardX: Float, forwardY: Float, forwardZ: Float): HomeMapAnchor =
            HomeMapAnchor(UUID.randomUUID().toString(), name.trim(), System.currentTimeMillis(),
                x, y, z, forwardX, forwardY, forwardZ)
    }
}

/**
 * Recovers the transform between a stored map and the current session's world frame.
 *
 * This is the deliberate trade-off of the semi-automatic route: the user states where they are
 * instead of the machine recognising the place. In exchange we get alignment that is exact by
 * construction, with no texture to fail on a white wall.
 */
object HomeMapRelocalizer {
    /**
     * Why a single anchor cannot be upgraded into a heading fix.
     *
     * Walking a known distance gives a travel bearing, but that bearing mixes "the direction I
     * walked" with "the direction the map faces", and the map's own orientation is exactly what
     * is unknown. Mixing them would produce a plausible, confidently wrong direction, which is the
     * one failure mode a blind user cannot detect. So the only safe upgrade is a second anchor.
     * Kept as documentation rather than a silent no-op method.
     */
    const val SINGLE_ANCHOR_CANNOT_FIX_HEADING = true

    /** A second anchor closer than this is treated as collinear and cannot fix a heading. */
    const val MIN_ANCHOR_SEPARATION_M = 0.5f
    /** A fit worse than this is refused rather than used to guide a blind user. */
    const val MAX_RESIDUAL_M = 0.5f

    /** One known anchor: translation only. Heading is explicitly reported as unknown. */
    fun alignBySingleAnchor(map: HomeMap, anchor: HomeMapAnchor, currentX: Float, currentZ: Float): HomeMapAlignment? {
        if (map.cellCount == 0) return null
        return HomeMapAlignment.translationOnly(currentX - anchor.x, currentZ - anchor.z, 0f)
    }

    /**
     * Two known anchors: solve the 2D rigid transform.
     *
     * Collinear pairs (or pairs closer than [MIN_ANCHOR_SEPARATION_M]) give an ambiguous yaw, so
     * they are refused instead of silently returning a plausible-looking wrong direction.
     */
    fun alignByTwoAnchors(map: HomeMap, first: HomeMapAnchor, firstHere: Pair<Float, Float>,
                           second: HomeMapAnchor, secondHere: Pair<Float, Float>): HomeMapAlignment? {
        if (map.cellCount == 0) return null
        // Both pairs are (x, z): take the same component from each, never x from one and z from
        // the other. Mixing them silently yields a plausible but completely wrong direction.
        val mapDx = second.x - first.x
        val mapDz = second.z - first.z
        val worldDx = secondHere.first - firstHere.first
        val worldDz = secondHere.second - firstHere.second
        val mapLength = kotlin.math.hypot(mapDx, mapDz)
        val worldLength = kotlin.math.hypot(worldDx, worldDz)
        if (mapLength < MIN_ANCHOR_SEPARATION_M || worldLength < MIN_ANCHOR_SEPARATION_M) return null
        // `mapToWorld` rotates by yaw, sending a map heading to (mapHeading - yaw), so matching
        // the observed world heading means subtracting the world angle from the map angle. Writing
        // it the other way round rotates backwards and drops the second anchor metres off target.
        val yaw = kotlin.math.atan2(mapDx, mapDz) - kotlin.math.atan2(worldDx, worldDz)
        val cos = kotlin.math.cos(yaw).toFloat()
        val sin = kotlin.math.sin(yaw).toFloat()
        val candidate = HomeMapAlignment(
            translateX = firstHere.first - (first.x * cos - first.z * sin),
            translateZ = firstHere.second - (first.x * sin + first.z * cos),
            cosYaw = cos, sinYaw = sin, headingKnown = true, residualMeters = 0f,
        )
        val residual = maxOf(
            kotlin.math.hypot(candidate.mapToWorldX(first.x, first.z) - firstHere.first,
                candidate.mapToWorldZ(first.x, first.z) - firstHere.second),
            kotlin.math.hypot(candidate.mapToWorldX(second.x, second.z) - secondHere.first,
                candidate.mapToWorldZ(second.x, second.z) - secondHere.second),
        )
        // A rigid fit of two points is exact, so a large residual means the inputs disagreed.
        if (residual > MAX_RESIDUAL_M) return null
        return candidate.copy(residualMeters = residual)
    }
}
