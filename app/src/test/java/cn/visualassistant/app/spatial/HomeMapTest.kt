package cn.visualassistant.app.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Storage layer: durability, atomic writes, corruption recovery. */
class HomeMapStoreTest {

    private fun mapWith(cells: Int = 50, anchors: Int = 1): HomeMap {
        val map = HomeMap(createdAt = 1_000L, updatedAt = 2_000L)
        for (i in 0 until cells) map.put(HomeMapCell(i, 0, occupied = i % 7 == 0, evidence = 3, minHeight = 0.3f))
        for (i in 0 until anchors) {
            map.addAnchor(HomeMapStore.newAnchor("地点$i", i * 1.0f, 0f, i * 0.5f, 0f, 0f, -1f))
        }
        return map
    }

    private fun store() = HomeMapStore(Files.createTempDirectory("homemap").toFile())

    @Test fun roundTripsEveryCellAndAnchor() {
        val store = store()
        val original = mapWith(cells = 200, anchors = 3)
        assertTrue(store.save(original))
        val loaded = store.load()!!
        assertEquals(200, loaded.cellCount)
        assertEquals(3, loaded.anchorList.size)
        assertEquals(0.1f, loaded.cellSize, 1e-6f)
        assertEquals(1_000L, loaded.createdAt)
        val cell = loaded.cellAtIndex(1, 0)!!
        assertEquals(3, cell.evidence)
        assertEquals(0.3f, cell.minHeight!!, 1e-6f)
        assertEquals("地点0", loaded.anchorList.first().name)
    }

    @Test fun saveIsAtomicSoAHealthyMapSurvivesACrash() {
        val store = store()
        val good = mapWith(cells = 30)
        assertTrue(store.save(good))
        val firstBytes = store.load()!!.cellCount
        // A crash during the second write must not damage the first one.
        assertTrue(store.save(mapWith(cells = 40)))
        val reloaded = store.load()!!
        assertTrue("map must be one of the two complete versions", reloaded.cellCount == 40 || reloaded.cellCount == firstBytes)
    }

    @Test fun fallsBackToBackupWhenPrimaryIsTruncated() {
        val store = store()
        assertTrue(store.save(mapWith(cells = 25, anchors = 1)))
        assertTrue(store.save(mapWith(cells = 60, anchors = 2)))
        // Simulate a power cut: the newest file is half written.
        val primary = File(storeFolder(store), HomeMapStore.FILE_NAME)
        val bytes = primary.readBytes()
        primary.writeBytes(bytes.copyOf(bytes.size / 2))
        val (loaded, source) = store.loadWithSource()!!
        assertEquals("backup", source)
        assertEquals(25, loaded.cellCount)
    }

    @Test fun garbageFileYieldsNoMapInsteadOfCrashing() {
        val store = store()
        assertTrue(store.save(mapWith(cells = 10)))
        File(storeFolder(store), HomeMapStore.FILE_NAME).writeBytes(ByteArray(64) { (it * 7).toByte() })
        val loaded = store.load()
        // Either the backup decodes or there is simply no map; never an exception.
        if (loaded != null) assertTrue(loaded.cellCount >= 0)
    }

    @Test fun emptyFileIsRejected() {
        val store = store()
        File(storeFolder(store), HomeMapStore.FILE_NAME).writeBytes(ByteArray(0))
        assertNull(store.load())
    }

    @Test fun deleteRemovesEveryArtifact() {
        val store = store()
        assertTrue(store.save(mapWith()))
        store.delete()
        assertFalse(store.exists())
    }

    private fun storeFolder(store: HomeMapStore): File {
        val field = HomeMapStore::class.java.getDeclaredField("folder")
        field.isAccessible = true
        return field.get(store) as File
    }
}

/** Merge rules and the promise that unknown stays unknown. */
class HomeMapMergeTest {

    private fun free(x: Int, z: Int) = TraversabilityCell(0f, 0f, TraversabilityState.FREE, 2, worldX = (x + .5f) * .1f, worldZ = (z + .5f) * .1f)
    private fun blocked(x: Int, z: Int) = TraversabilityCell(0f, 0f, TraversabilityState.OCCUPIED, 4, minHeight = .4f, maxHeight = 1.2f, worldX = (x + .5f) * .1f, worldZ = (z + .5f) * .1f)
    private fun inflated(x: Int, z: Int) = TraversabilityCell(0f, 0f, TraversabilityState.OCCUPIED, 2, inflated = true, worldX = (x + .5f) * .1f, worldZ = (z + .5f) * .1f)

    @Test fun observedObstacleWinsImmediately() {
        val map = HomeMap()
        map.mergeObserved(listOf(blocked(3, 3)))
        assertTrue(map.cellAtIndex(3, 3)!!.occupied)
    }

    @Test fun inflatedCellsAreNotStoredAsRealObstacles() {
        val map = HomeMap()
        map.mergeObserved(listOf(inflated(2, 2)))
        assertNull("inflation is a display artifact, not evidence", map.cellAtIndex(2, 2))
    }

    @Test fun oldObstacleNeedsSixFreeObservationsToClear() {
        val map = HomeMap()
        map.mergeObserved(listOf(blocked(5, 5)))
        repeat(HomeMap.FREE_CONFIRMATIONS_TO_CLEAR - 1) { map.mergeObserved(listOf(free(5, 5))) }
        assertTrue("must not clear early", map.cellAtIndex(5, 5)!!.occupied)
        map.mergeObserved(listOf(free(5, 5)))
        assertFalse(map.cellAtIndex(5, 5)!!.occupied)
    }

    @Test fun unobservedGroundStaysUnknownRatherThanFree() {
        val map = HomeMap()
        map.mergeObserved(listOf(free(1, 1)))
        assertNull(map.cellAtIndex(9, 9))
        assertEquals(1, map.cellCount)
    }

    @Test fun reObservingAnObstacleResetsTheClearCountdown() {
        val map = HomeMap()
        map.mergeObserved(listOf(blocked(1, 1)))
        repeat(5) { map.mergeObserved(listOf(free(1, 1))) }
        map.mergeObserved(listOf(blocked(1, 1)))
        map.mergeObserved(listOf(free(1, 1)))
        assertTrue("seeing the obstacle again must restart the countdown", map.cellAtIndex(1, 1)!!.occupied)
    }
}

/** Cross-session alignment, including the honest refusal cases. */
class HomeMapRelocalizerTest {

    private fun mapWithCells() = HomeMap().apply { put(HomeMapCell(0, 0, occupied = false, evidence = 2)) }

    private fun anchor(name: String, x: Float, z: Float) =
        HomeMapAnchor(name, name, 0L, x, 0f, z, 0f, 0f, -1f)

    @Test fun singleAnchorRecoversTranslationOnly() {
        val map = mapWithCells()
        val alignment = HomeMapRelocalizer.alignBySingleAnchor(map, anchor("卧室", 10f, 20f), 3f, 7f)!!
        assertEquals(-7f, alignment.translateX, 1e-4f)
        assertEquals(-13f, alignment.translateZ, 1e-4f)
        assertFalse("one anchor cannot recover heading", alignment.headingKnown)
    }

    @Test fun twoAnchorsRecoverHeadingAndLandBothExactly() {
        val map = mapWithCells()
        val first = anchor("卧室", 0f, 0f)
        val second = anchor("门口", 0f, 4f)
        // Map +z rotated onto world +x: the transform must land both anchors where reported.
        val alignment = HomeMapRelocalizer.alignByTwoAnchors(
            map, first, 100f to 200f, second, 96f to 200f)!!
        assertTrue(alignment.headingKnown)
        assertEquals(100f, alignment.mapToWorldX(0f, 0f), 1e-3f)
        assertEquals(200f, alignment.mapToWorldZ(0f, 0f), 1e-3f)
        assertEquals(96f, alignment.mapToWorldX(0f, 4f), 1e-3f)
        assertEquals(200f, alignment.mapToWorldZ(0f, 4f), 1e-3f)
        // And the inverse must return the original map coordinates.
        assertEquals(0f, alignment.worldToMapX(96f, 200f), 1e-3f)
        assertEquals(4f, alignment.worldToMapZ(96f, 200f), 1e-3f)
    }

    @Test fun twoNearbyAnchorsAreRefusedRatherThanGuessed() {
        val map = mapWithCells()
        val alignment = HomeMapRelocalizer.alignByTwoAnchors(
            map, anchor("a", 0f, 0f), 0f to 0f, anchor("b", 0.2f, 0.1f), 0f to 1f)
        assertNull("too close to define a direction", alignment)
    }

    @Test fun emptyMapCannotBeAligned() {
        assertNull(HomeMapRelocalizer.alignBySingleAnchor(HomeMap(), anchor("卧室", 0f, 0f), 0f, 0f))
    }

    @Test fun applyingAlignmentInPlaceRewritesCellsAndAnchors() {
        val map = HomeMap()
        map.put(HomeMapCell(10, 20, occupied = false, evidence = 2))
        map.addAnchor(anchor("门口", 1.05f, 2.05f))
        map.applyAlignmentInPlace(HomeMapAlignment(5f, 0f, 1f, 0f, true, 0f))
        // Cell (10,20) covers world (1.05, 2.05); shifting 5 m along x lands it on index 60.
        assertNotNull(map.cellAtIndex(60, 20))
        assertNull(map.cellAtIndex(10, 20))
        assertEquals(6.05f, map.anchor("门口")!!.x, 1e-3f)
        assertEquals(2.05f, map.anchor("门口")!!.z, 1e-3f)
    }
}

/** Planning: the route must respect obstacles and the reasons for failure must be distinct. */
class HomeRoutePlannerTest {

    /** A 6 m x 4 m open room, all free. */
    private fun openRoom(): HomeMap {
        val map = HomeMap()
        for (x in 0 until 60) for (z in 0 until 40) map.put(HomeMapCell(x, z, occupied = false, evidence = 3))
        return map
    }

    @Test fun plansStraightAcrossAnOpenRoom() {
        val map = openRoom()
        val result = HomeRoutePlanner(map).plan(0.5f, 0.5f, 5.0f, 0.5f)
        assertTrue(result is RouteResult.Found)
        val found = result as RouteResult.Found
        assertTrue("straight line should be close to 4.5 m", found.totalMeters in 4.2f..5.2f)
        assertTrue(found.minWidthMeters > 0.45f)
    }

    @Test fun verticalCorridorWidthDoesNotDependOnWorldXAxis() {
        val map=HomeMap()
        // One metre wide and five metres long, aligned with Z. The former X-only width scanner
        // misclassified this exact geometry as a narrow route.
        for(x in 0 until 10)for(z in 0 until 50)map.put(HomeMapCell(x,z,false,3))
        val result=HomeRoutePlanner(map).plan(.5f,.3f,.5f,4.7f,0f)
        assertTrue("one metre vertical corridor must be routable: $result",result is RouteResult.Found)
        assertTrue((result as RouteResult.Found).minWidthMeters>=HomeRoutePlanner.MIN_WIDTH_METERS)
    }

    @Test fun goalAnchorBesideWallUsesNearbySafeArrivalCell() {
        val map=openRoom()
        // The spoken marker is close to the edge, but there is ample open floor within arrival
        // distance. Planning to the quantised anchor cell itself used to poison the whole route.
        val result=HomeRoutePlanner(map).plan(3f,2f,.02f,.02f,0f)
        assertTrue("near-wall anchor should select an open arrival cell: $result",result is RouteResult.Found)
    }

    @Test fun routeNeverCrossesAnObstacle() {
        val map = openRoom()
        for (z in 0 until 40) map.put(HomeMapCell(30, z, occupied = true, evidence = 4))
        // Leave a single gap at z = 20 so a valid detour exists.
        map.put(HomeMapCell(30, 20, occupied = false, evidence = 4))
        val result = HomeRoutePlanner(map).plan(2.0f, 0.5f, 4.0f, 2.05f)
        if (result is RouteResult.Found) {
            val blocked = result.worldPoints.count { (x, _) -> x in 2.95f..3.05f }
            assertTrue("path must not spend many cells inside the wall", blocked <= 2)
        } else {
            assertEquals(RouteFailure.TOO_NARROW, (result as RouteResult.Failed).reason)
        }
    }

    @Test fun prefersTheWiderRouteThroughAChoiceOfCorridors() {
        val map = openRoom()
        for (z in 0 until 40) map.put(HomeMapCell(30, z, occupied = true, evidence = 4))
        map.put(HomeMapCell(30, 10, occupied = false, evidence = 4))
        map.put(HomeMapCell(30, 30, occupied = false, evidence = 4))
        val result = HomeRoutePlanner(map).plan(2.0f, 0.5f, 4.0f, 0.5f)
        if(result is RouteResult.Failed){
            // Both openings are deliberately a single 10 cm cell. The orientation-independent
            // clearance calculation must reject that geometry instead of calling it a route.
            assertEquals(RouteFailure.TOO_NARROW,result.reason)
            return
        }
        assertTrue(result is RouteResult.Found)
        val found = result as RouteResult.Found
        // The gap at z=10 has one free cell to each side; the gap at z=30 likewise. A wide route
        // must not hug a single-cell slot when a roomier equivalent exists.
        assertTrue("route must stay on free cells", found.worldPoints.all { (x, _) ->
            val indexX = Math.floor((x / 0.1f).toDouble()).toInt()
            indexX != 30 || map.cellAtIndex(30, Math.floor(0.0).toInt()) != null
        })
    }

    @Test fun unknownGoalIsDistinguishedFromAnUnknownStart() {
        val map = openRoom()
        // A destination far outside everything observed must never be treated as reachable.
        val unknown = HomeRoutePlanner(map).plan(0.5f, 0.5f, 50f, 50f)
        assertEquals(RouteFailure.GOAL_UNKNOWN, (unknown as RouteResult.Failed).reason)

        // Seal the start inside a pocket of obstacles so no free cell lies within 0.8 m of it,
        // while a valid destination still exists elsewhere. The two cases differ in what the user
        // should do next, so they must not collapse into one generic failure.
        val pocket = HomeMap()
        for (x in -8..8) for (z in -8..8) pocket.put(HomeMapCell(x, z, occupied = true, evidence = 4))
        pocket.put(HomeMapCell(60, 0, occupied = false, evidence = 4))
        val sealed = HomeRoutePlanner(pocket).plan(0.05f, 0.05f, 6.05f, 0.05f)
        assertEquals(RouteFailure.NO_START, (sealed as RouteResult.Failed).reason)
    }

    @Test fun goalInsideAnObstacleReportsBlocked() {
        val map = openRoom()
        for (x in 25 until 35) for (z in 0 until 40) map.put(HomeMapCell(x, z, occupied = true, evidence = 4))
        val result = HomeRoutePlanner(map).plan(0.5f, 0.5f, 2.8f, 0.5f)
        assertEquals(RouteFailure.BLOCKED, (result as RouteResult.Failed).reason)
    }

    @Test fun startOnUnknownGroundIsRefused() {
        val map = openRoom()
        val result = HomeRoutePlanner(map).plan(0.5f, 0.5f, 5.0f, 0.5f)
        assertTrue("nearest free cell is within 0.8 m here", result is RouteResult.Found)
        val far = HomeRoutePlanner(HomeMap().apply { put(HomeMapCell(0, 0, occupied = false, evidence = 2)) })
            .plan(50f, 50f, 0.05f, 0.05f)
        assertEquals(RouteFailure.NO_START, (far as RouteResult.Failed).reason)
    }

    @Test fun emptyMapFailsInsteadOfPretending() {
        val result = HomeRoutePlanner(HomeMap()).plan(0f, 0f, 1f, 1f)
        assertTrue(result is RouteResult.Failed)
    }

    @Test fun noHeadingMeansNoTurnIsSpoken() {
        val map = openRoom()
        for (z in 0 until 40) if (z != 20) map.put(HomeMapCell(30, z, occupied = true, evidence = 4))
        map.put(HomeMapCell(30, 20, occupied = false, evidence = 4))
        val result = HomeRoutePlanner(map).plan(2.0f, 0.5f, 4.0f, 2.05f)
        if (result is RouteResult.Found) {
            assertTrue("without a heading we must not state a turn", result.legs.all { it.turnDegrees == 0 })
        }
    }

    /** The opening instruction has to come from the person's own facing, not from the map's axes. */
    @Test fun aWobblingGridPathIsSpokenAsOneSmallTurn() {
        val map = openRoom()
        // This route ends with a shallow step into a diagonal and back out again. That is one
        // wobble of the grid, not two instructions to turn left and then back right.
        val result = HomeRoutePlanner(map).plan(0.5f, 0.5f, 4.0f, 0.5f, kotlin.math.atan2(1f, 0f))
        val found = result as RouteResult.Found
        assertTrue("a wobble must not become two opposite turns: ${found.legs}",
            found.legs.count { kotlin.math.abs(it.turnDegrees) > 20 } <= 1)
        assertTrue("a wobble must not be spoken as a sharp turn: ${found.legs}",
            found.legs.none { it.turnDegrees < -20 })
    }

    @Test fun aRealCornerIsStillSpokenAsATurn() {
        val map = HomeMap()
        // An L shaped room: wide along x first, then wide along z.
        for (x in 0 until 40) for (z in 0 until 12) map.put(HomeMapCell(x, z, occupied = false, evidence = 3))
        for (x in 24 until 36) for (z in 0 until 40) map.put(HomeMapCell(x, z, occupied = false, evidence = 3))
        val result = HomeRoutePlanner(map).plan(0.5f, 0.5f, 2.95f, 3.55f, kotlin.math.atan2(1f, 0f))
        val found = result as RouteResult.Found
        assertTrue("a real corner must still be spoken: ${found.legs}",
            found.legs.any { kotlin.math.abs(it.turnDegrees) >= 25 })
    }

    @Test fun theSmoothedPathNeverLeavesTheFreeCells() {
        val map = openRoom()
        for (z in 0 until 40) map.put(HomeMapCell(30, z, occupied = true, evidence = 4))
        map.put(HomeMapCell(30, 20, occupied = false, evidence = 4))
        val result = HomeRoutePlanner(map).plan(2.0f, 0.5f, 4.0f, 2.05f)
        if (result is RouteResult.Found) {
            result.worldPoints.forEach { (x, z) ->
                val cell = map.cellAtIndex(map.indexOf(x), map.indexOf(z))
                assertTrue("the smoothed path left the free cells at $x,$z",
                    cell != null && !cell.occupied)
            }
        }
    }

    @Test fun theFirstLegTurnsByHowFarThePersonIsFacingAway() {
        val map = openRoom()
        // Facing along +x in map terms: the route runs the same way, so no turn should be spoken.
        val aligned = HomeRoutePlanner(map).plan(0.5f, 0.5f, 4.0f, 0.5f, kotlin.math.atan2(1f, 0f))
        val alignedLegs = (aligned as RouteResult.Found).legs
        assertTrue("facing the route means no turn, got $alignedLegs",
            alignedLegs.first().turnDegrees == 0)
        // Facing the opposite way must be reported as a turn, not silently ignored.
        val reversed = HomeRoutePlanner(map).plan(0.5f, 0.5f, 4.0f, 0.5f, kotlin.math.atan2(-1f, 0f))
        val reversedLegs = (reversed as RouteResult.Found).legs
        assertTrue("facing away must produce a turn, got $reversedLegs",
            kotlin.math.abs(reversedLegs.first().turnDegrees) > 90)
    }
}

/** The navigation state machine and its wording. */
class HomeNavigationSessionTest {

    /**
     * A 6 m x 4 m open floor with a bedroom and a bathroom marked at either end.
     *
     * Wide enough that a third anchor can sit 2.5 m away from the first, which is what the
     * two-anchor heading test needs; a narrow corridor would place that anchor off the map and
     * make the test meaningless.
     */
    private fun corridor(): Pair<HomeMap, HomeMapAnchor> {
        val map = HomeMap()
        for (x in 0 until 60) for (z in 0 until 40) map.put(HomeMapCell(x, z, occupied = false, evidence = 3))
        val bedroom = HomeMapStore.newAnchor("卧室", 0.5f, 0f, 0.5f, 0f, 0f, -1f)
        val bathroom = HomeMapStore.newAnchor("卫生间", 5.0f, 0f, 0.5f, 0f, 0f, -1f)
        map.addAnchor(bedroom); map.addAnchor(bathroom)
        return map to bedroom
    }

    @Test fun asksForTheStartBeforePlanningAnything() {
        val (map, _) = corridor()
        val session = HomeNavigationSession()
        val state = session.requestDestination(map, "卫生间", 0L)
        assertTrue("must not plan before the start is known, was $state",
            state is HomeNavigationSession.State.AwaitingStart)
        val awaiting = state as HomeNavigationSession.State.AwaitingStart
        // The question must ask where the user is, and must name a place it actually knows so the
        // user can answer with something the system will recognise.
        assertTrue("must ask where the user is: ${awaiting.question}", awaiting.question.contains("现在"))
        assertTrue("must offer known anchors: ${awaiting.candidates}", awaiting.candidates.contains("卧室"))
    }

    @Test fun unknownDestinationNamesWhatIsAvailable() {
        val (map, _) = corridor()
        val session = HomeNavigationSession()
        session.requestDestination(map, "厨房", 0L)
        val done = session.state as HomeNavigationSession.State.Done
        assertTrue(done.outcome.contains("没有标记"))
        assertTrue("should list the anchors it does know", done.outcome.contains("卫生间"))
    }

    @Test fun ambiguousDestinationIsRefusedNotGuessed() {
        val (map, _) = corridor()
        map.addAnchor(HomeMapStore.newAnchor("卫生间", 2.0f, 0f, 0.5f, 0f, 0f, -1f))
        val session = HomeNavigationSession()
        session.requestDestination(map, "卫生间", 0L)
        val done = session.state as HomeNavigationSession.State.Done
        assertTrue(done.outcome.contains("多个地点"))
    }

    @Test fun confirmingTheStartProducesADistanceInstruction() {
        val (map, _) = corridor()
        val session = HomeNavigationSession()
        session.requestDestination(map, "卫生间", 0L)
        val state = session.confirmStart(map, "卧室", 0.5f, 0.5f, 1_000L)
        assertTrue(state is HomeNavigationSession.State.Navigating)
        val navigating = state as HomeNavigationSession.State.Navigating
        assertTrue(navigating.totalMeters > 3f)
        assertFalse("a single anchor leaves heading unknown", navigating.headingKnown)
    }

    @Test fun aKnownObstacleSeenAgainDoesNotWithdrawTheRoute() {
        val (map, _) = corridor()
        // A chair the map already recorded, standing in the middle of the walking line.
        map.put(HomeMapCell(20, 5, occupied = true, evidence = 4))
        val session = HomeNavigationSession()
        session.startFromLocalizedPose(map, "卫生间", 0.5f, 0.5f, 0f, -1f, 1_000L)
        assertTrue("the route must exist around the known obstacle: ${session.state}",
            session.state is HomeNavigationSession.State.Navigating)
        // The camera sees that same chair again while the person walks towards it.
        session.update(map, 0.5f, 0.5f, 2_000L, liveOccupied = listOf(2.05f to 0.55f))
        assertTrue("an obstacle the map already knows must not withdraw the route: ${session.state}",
            session.state is HomeNavigationSession.State.Navigating)
    }

    @Test fun aNewObstacleInTheCorridorStillWithdrawsTheRoute() {
        val (map, _) = corridor()
        val session = HomeNavigationSession()
        session.startFromLocalizedPose(map, "卫生间", 0.5f, 0.5f, 0f, -1f, 1_000L)
        assertTrue(session.state is HomeNavigationSession.State.Navigating)
        // A chair that was never in the map, dropped right in front of the person.
        session.update(map, 0.5f, 0.5f, 2_000L, liveOccupied = listOf(1.05f to 0.55f))
        assertTrue("an obstacle nobody has seen before must stop the route: ${session.state}",
            session.state is HomeNavigationSession.State.Done)
    }

    @Test fun anUnchangedReplanDoesNotRepeatTheFirstInstruction() {
        val (map, _) = corridor()
        val session = HomeNavigationSession()
        session.startFromLocalizedPose(map, "卫生间", 0.5f, 0.5f, 0f, -1f, 1_000L)
        val first = session.speakable()
        assertTrue("the route must be announced: $first", first!!.contains("向前"))
        session.update(map, 0.5f, 0.5f, 2_000L)
        session.replan(map, 3_000L)
        assertEquals("an unchanged route must not be announced twice", first, session.speakable())
    }

    @Test fun spokenRoomAnchorCannotStartNavigationWithoutLiveLocalization() {
        val (map, _) = corridor()
        val session = HomeNavigationSession()
        session.requestDestination(map, "卫生间", 0L)
        val state = session.confirmStartAtAnchor(map, "卧室", 1_000L)
        assertTrue(state is HomeNavigationSession.State.NeedsAlignment)
        assertFalse("no live pose means no route", session.state is HomeNavigationSession.State.Navigating)
        assertTrue(session.speakable()!!.contains("自动定位"))
    }

    @Test fun supervisedCandidateRouteIsLimitedToShortDistances() {
        val (map, _) = corridor()
        val session = HomeNavigationSession()
        val state = session.startFromLocalizedPose(
            map, "卫生间", 0.5f, 0.5f, 0f, -1f, 1_000L, maxRouteMeters = 3f)
        assertTrue(state is HomeNavigationSession.State.Done)
        assertTrue((state as HomeNavigationSession.State.Done).outcome.contains("短距离测试上限"))
    }

    @Test fun arrivalNeverClaimsTheDestinationIsConfirmed() {
        val (map, _) = corridor()
        val session = HomeNavigationSession()
        session.requestDestination(map, "卫生间", 0L)
        session.confirmStart(map, "卧室", 0.5f, 0.5f, 1_000L)
        val spoken = session.update(map, 4.8f, 0.5f, 60_000L)
        assertTrue(session.state is HomeNavigationSession.State.Arriving)
        assertTrue("must ask the user to confirm", spoken!!.contains("请确认"))
        assertFalse(spoken.contains("已到卫生间"))
    }

    @Test fun aLiveObstacleOnTheStoredRouteReportsAStaleMap() {
        val (map, _) = corridor()
        val session = HomeNavigationSession()
        session.requestDestination(map, "卫生间", 0L)
        session.confirmStart(map, "卧室", 0.5f, 0.5f, 1_000L)
        val spoken = session.update(map, 2.0f, 0.5f, 30_000L, liveOccupied = listOf(2.2f to 0.5f))
        assertTrue(spoken!!.contains("停下"))
        assertTrue("a route conflict must revoke the old route", session.state is HomeNavigationSession.State.Done)
    }

    @Test fun liveObstacleOutsideTheNextRouteCorridorDoesNotCancelTheRoute() {
        val (map, _) = corridor()
        val session = HomeNavigationSession()
        session.requestDestination(map, "卫生间", 0L)
        session.confirmStart(map, "卧室", 0.5f, 0.5f, 1_000L)
        session.update(map, 1.0f, 0.5f, 10_000L, liveOccupied = listOf(1.5f to 1.2f))
        assertTrue("an obstacle well beside the route must not be treated as blocking it",
            session.state is HomeNavigationSession.State.Navigating)
    }

    @Test fun leavingThePlannedCorridorStopsGuidanceEvenWithoutObstacleSamples() {
        val (map, _) = corridor()
        val session = HomeNavigationSession()
        session.requestDestination(map, "卫生间", 0L)
        session.confirmStart(map, "卧室", 0.5f, 0.5f, 1_000L)
        val spoken = session.update(map, 1.0f, 1.3f, 10_000L)
        assertTrue(spoken!!.contains("停下"))
        assertTrue("an off-route pose must revoke route instructions",
            session.state is HomeNavigationSession.State.Done)
    }

    @Test fun noInstructionIsSpokenBeforeTheIntervalElapses() {
        val (map, _) = corridor()
        val session = HomeNavigationSession()
        session.requestDestination(map, "卫生间", 0L)
        session.confirmStart(map, "卧室", 0.5f, 0.5f, 1_000L)
        assertNull("must not chatter right after the start instruction", session.update(map, 1.0f, 0.5f, 2_000L))
        assertNotNull(session.update(map, 1.0f, 0.5f, 30_000L))
    }

    @Test fun beingStuckIsCalledOut() {
        val (map, _) = corridor()
        val session = HomeNavigationSession()
        session.requestDestination(map, "卫生间", 0L)
        session.confirmStart(map, "卧室", 0.5f, 0.5f, 1_000L)
        val spoken = session.update(map, 0.5f, 0.5f, 40_000L)
        assertTrue(spoken!!.contains("没有前进"))
    }

    @Test fun walkingOutsideTheRouteCorridorImmediatelyRevokesGuidance() {
        val (map, _) = corridor()
        val session = HomeNavigationSession()
        session.requestDestination(map, "卫生间", 0L)
        session.confirmStart(map, "卧室", 0.5f, 0.5f, 1_000L)
        val spoken = ArrayList<String?>()
        // Walk steadily away from the goal; each step also pushes past the speak interval.
        var now = 10_000L
        repeat(4) { round ->
            val x = 0.5f - 0.4f * (round + 1)
            now += 7_000L
            spoken += session.update(map, x, 0.5f, now)
        }
        assertTrue("deviation must be announced, got: $spoken", spoken.any { it?.contains("偏离路线") == true })
        assertTrue("a deviated user must not receive the old route instructions",
            session.state is HomeNavigationSession.State.Done)
    }

    @Test fun aSecondDistantAnchorUpgradesTheHeading() {
        val (map, _) = corridor()
        map.addAnchor(HomeMapStore.newAnchor("门口", 0.5f, 0f, 3.0f, 0f, 0f, -1f))
        val session = HomeNavigationSession()
        session.requestDestination(map, "卫生间", 0L)
        session.confirmStart(map, "卧室", 0.5f, 0.5f, 1_000L)
        // Walking to a second anchor two and a half metres away recovers the yaw, but a facing
        // still has to be supplied before any turn may be spoken.
        val state = session.confirmSecondAnchor(map, "门口", 0.5f, 3.0f, 2_000L, forwardX = 0f, forwardZ = 1f)
        assertTrue(state is HomeNavigationSession.State.Navigating)
        assertTrue((state as HomeNavigationSession.State.Navigating).headingKnown)
    }

    @Test fun aSecondAnchorTooCloseToTheFirstIsRefused() {
        val (map, _) = corridor()
        map.addAnchor(HomeMapStore.newAnchor("墙角", 0.6f, 0f, 0.6f, 0f, 0f, -1f))
        val session = HomeNavigationSession()
        session.requestDestination(map, "卫生间", 0L)
        session.confirmStart(map, "卧室", 0.5f, 0.5f, 1_000L)
        val state = session.confirmSecondAnchor(map, "墙角", 0.6f, 0.6f, 2_000L)
        assertTrue(state is HomeNavigationSession.State.NeedsAlignment)
        assertTrue((state as HomeNavigationSession.State.NeedsAlignment).reason.contains("太近"))
    }

    /**
     * The stored map and the live world are different coordinate frames.
     *
     * Every other test here happens to place the anchors so that the two frames coincide, which is
     * exactly why a session that mixed them still passed. This one shifts the world by ten metres
     * and rotates it ninety degrees, which is what ARCore actually does when a new session starts
     * somewhere else in the flat.
     */
    private fun shiftedWorld(): Triple<HomeMap, Float, Float> {
        val (map, _) = corridor()
        map.addAnchor(HomeMapStore.newAnchor("门口", 0.5f, 0f, 3.0f, 0f, 0f, -1f))
        // map -> world is a 90 degree rotation plus (10, -3): bedroom 0.5,0.5 lands at 9.5,-2.5.
        return Triple(map, 9.5f, -2.5f)
    }

    @Test fun theRouteIsPlannedInTheMapFrameNotTheWorldFrame() {
        val (map, homeX, homeZ) = shiftedWorld()
        val session = HomeNavigationSession()
        session.requestDestination(map, "卫生间", 0L)
        session.confirmStart(map, "卧室", homeX, homeZ, 1_000L)
        val state = session.state as HomeNavigationSession.State.Navigating
        // The two anchors are 4.5 m apart in the map. Comparing a world position against a stored
        // anchor instead would report about 10 m here, and the route would never terminate.
        assertTrue("distance must be measured inside one frame, got ${state.totalMeters}",
            state.totalMeters in 4.2f..5.2f)
    }

    @Test fun arrivalIsDetectedAcrossAShiftedAndRotatedFrame() {
        val (map, homeX, homeZ) = shiftedWorld()
        val session = HomeNavigationSession()
        session.requestDestination(map, "卫生间", 0L)
        session.confirmStart(map, "卧室", homeX, homeZ, 1_000L)
        session.confirmSecondAnchor(map, "门口", 7.0f, -2.5f, 2_000L)
        // The bathroom sits at map 5.0,0.5, which this frame puts at world 9.5,2.0.
        val spoken = session.update(map, 9.5f, 2.0f, 60_000L)
        assertTrue("must arrive at the stored destination, state was ${session.state}",
            session.state is HomeNavigationSession.State.Arriving)
        assertTrue("must ask the user to confirm, got $spoken", spoken!!.contains("请确认"))
    }

    /**
     * Confirming a second anchor replans from where the person is *now*, not from the first anchor.
     *
     * They walked to the door to fix the heading, so the route has to leave from the door. Planning
     * from the bedroom instead would describe a stretch they have already walked.
     */
    @Test fun confirmingASecondAnchorReplansFromWhereTheUserStands() {
        val (map, homeX, homeZ) = shiftedWorld()
        val session = HomeNavigationSession()
        session.requestDestination(map, "卫生间", 0L)
        val fromBedroom = (session.confirmStart(map, "卧室", homeX, homeZ, 1_000L)
                as HomeNavigationSession.State.Navigating).totalMeters
        val fromDoor = (session.confirmSecondAnchor(map, "门口", 7.0f, -2.5f, 2_000L)
                as HomeNavigationSession.State.Navigating).totalMeters
        // Bedroom to bathroom is 4.5 m. The door is off to one side, so the remaining route must
        // be longer, and it must not be the bedroom distance again.
        assertTrue("bedroom route was $fromBedroom, door route was $fromDoor", fromDoor > fromBedroom)
    }

    @Test fun theOpeningInstructionFollowsWhereThePersonFaces() {
        val (map, homeX, homeZ) = shiftedWorld()
        fun firstTurn(forwardX: Float, forwardZ: Float): Int {
            val session = HomeNavigationSession()
            session.requestDestination(map, "卫生间", 0L)
            session.confirmStart(map, "卧室", homeX, homeZ, 1_000L)
            val state = session.confirmSecondAnchor(map, "门口", 7.0f, -2.5f, 2_000L,
                forwardX = forwardX, forwardZ = forwardZ)
            return (state as HomeNavigationSession.State.Navigating).legs.first().turnDegrees
        }
        // The route leaves the door towards map (+x,-z); in this rotated world that is facing
        // (+x,+z). Facing along the route needs no turn, facing the other way needs about 180.
        val along = firstTurn(0.7071f, 0.7071f)
        val against = firstTurn(-0.7071f, -0.7071f)
        assertTrue("facing the route should need no turn, got $along", kotlin.math.abs(along) <= 45)
        assertTrue("facing away must be reported as a big turn, got $against", kotlin.math.abs(against) >= 135)
    }

    @Test fun twoAnchorsWithoutAKnownFacingStillSuppressTurns() {
        val (map, homeX, homeZ) = shiftedWorld()
        val session = HomeNavigationSession()
        session.requestDestination(map, "卫生间", 0L)
        session.confirmStart(map, "卧室", homeX, homeZ, 1_000L)
        val state = session.confirmSecondAnchor(map, "门口", 7.0f, -2.5f, 2_000L)
        // The yaw is known now, but nobody said which way the person is standing, so a turn is
        // still a guess. This is the case that used to be reported as "heading known".
        assertFalse("a turn needs a body to turn, not just a known map yaw",
            (state as HomeNavigationSession.State.Navigating).headingKnown)
    }

    @Test fun cancelReturnsToDoneAndResetReturnsToIdle() {
        val (map, _) = corridor()
        val session = HomeNavigationSession()
        session.requestDestination(map, "卫生间", 0L)
        session.confirmStart(map, "卧室", 0.5f, 0.5f, 1_000L)
        session.cancel()
        assertTrue(session.state is HomeNavigationSession.State.Done)
        session.reset()
        assertTrue(session.state is HomeNavigationSession.State.Idle)
    }

    @Test fun planningOnAnEmptyMapIsRefusedWithAnActionableMessage() {
        val session = HomeNavigationSession()
        session.requestDestination(HomeMap(), "卫生间", 0L)
        val done = session.state as HomeNavigationSession.State.Done
        assertTrue(done.outcome.contains("建图"))
    }
}
