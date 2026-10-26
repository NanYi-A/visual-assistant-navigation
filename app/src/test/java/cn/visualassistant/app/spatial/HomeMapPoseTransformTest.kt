package cn.visualassistant.app.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeMapPoseTransformTest {
    @Test fun appliesBackendRowMajorRigidTransformToPointAndDirection() {
        // +90 degrees around Y, then translate by (10, 2, -3).
        val transform = HomeMapPoseTransform("home", floatArrayOf(
            0f, 0f, 1f, 10f,
            0f, 1f, 0f, 2f,
            -1f, 0f, 0f, -3f,
            0f, 0f, 0f, 1f,
        ), 0L, 4, 100, .4f, .03f)
        val point = transform.point(1f, 2f, 3f)
        assertEquals(13f, point[0], 1e-4f)
        assertEquals(4f, point[1], 1e-4f)
        assertEquals(-4f, point[2], 1e-4f)
        val direction = transform.direction(0f, 0f, -1f)
        assertEquals(-1f, direction[0], 1e-4f)
        assertEquals(0f, direction[2], 1e-4f)
    }

    @Test fun localizedPoseStartsFromMeasuredPositionWithoutAskingForRoom() {
        val map = HomeMap()
        for (x in 0 until 60) for (z in 0 until 20) map.put(HomeMapCell(x,z,false,3))
        val bathroom = HomeMapStore.newAnchor("卫生间",5f,0f,.5f,0f,0f,-1f)
        map.addAnchor(bathroom)
        val session = HomeNavigationSession()
        val state = session.startFromLocalizedPose(map,"卫生间",.5f,.5f,1f,0f,1_000L)
        assertTrue(state is HomeNavigationSession.State.Navigating)
        val navigating = state as HomeNavigationSession.State.Navigating
        assertTrue(navigating.headingKnown)
        assertTrue(navigating.totalMeters > 4f)
    }
}
