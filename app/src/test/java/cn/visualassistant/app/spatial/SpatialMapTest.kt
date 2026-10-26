package cn.visualassistant.app.spatial

import org.junit.Assert.*
import org.junit.Test

class SpatialMapTest {
    private fun identity(x: Float=0f, z: Float=0f) = CameraTransform(floatArrayOf(
        1f,0f,0f,0f, 0f,1f,0f,0f, 0f,0f,1f,0f, x,0f,z,1f))
    private fun samples(p: Point3=Point3(.05f,.05f,-2f), label: String?="椅子") =
        listOf(WorldSample(p,1,100,label,classId=label?.let { 56 },confidence=label?.let { .9f },depthSupport=8,depthSpread=.03f),
            WorldSample(p,2,300,label,classId=label?.let { 56 },confidence=label?.let { .9f },depthSupport=8,depthSpread=.03f))

    @Test fun projectionUsesOpticalDepthNotRayLength() {
        val point=DepthGeometry.cameraPoint(600f,200f,2f,400f,400f,400f,200f)!!
        assertEquals(1f,point.x,.001f); assertEquals(-2f,point.z,.001f)
        assertTrue(point.length()>2f)
        assertNull(DepthGeometry.cameraPoint(0f,0f,0f,400f,400f,0f,0f))
        assertNull(DepthGeometry.cameraPoint(0f,0f,2f,0f,400f,0f,0f))
        assertNull(DepthGeometry.cameraPoint(Float.NaN,0f,2f,400f,400f,0f,0f))
    }
    @Test fun translationChangesRelativeDistanceWithoutMovingWorldObject() {
        val original=SpatialMap.build(samples(),identity(),400).marks.single()
        val moved=SpatialMap.build(samples(),identity(z=-1f),400).marks.single()
        assertEquals(-2f,original.relative.z,.001f)
        assertEquals(-1f,moved.relative.z,.001f)
    }
    @Test fun turnRightMovesOldFrontObjectToLeft() {
        val rightTurn=CameraTransform(floatArrayOf(0f,0f,1f,0f, 0f,1f,0f,0f, -1f,0f,0f,0f, 0f,0f,0f,1f))
        val relative=rightTurn.relative(Point3(0f,0f,-2f))!!
        assertEquals(-2f,relative.x,.001f)
        assertEquals("左侧",SpatialSnapshot.sector(relative))
    }
    @Test fun duplicateSamplesWithinOneFrameCannotConfirmSurface() {
        val input=List(50) { WorldSample(Point3(.05f,.05f,-2f),1,100) }
        assertTrue(SpatialMap.build(input,identity(),400).marks.isEmpty())
    }
    @Test fun expiredAndOutOfRangeCellsDisappearWithoutFreeSpaceClaim() {
        assertTrue(SpatialMap.build(samples(),identity(),16000).marks.isEmpty())
        assertTrue(SpatialMap.build(samples(Point3(0f,0f,-8f)),identity(),400).marks.isEmpty())
        val answer=SpatialMap.build(emptyList(),identity(),400).describe("前面怎么走",500)
        assertTrue(answer.contains("暂不能提供方向和距离"))
        assertFalse(SpatialMap.build(emptyList(),identity(),400).usable)
    }
    @Test fun lostTrackingOrStalePoseCannotAnswerDistance() {
        assertTrue(SpatialSnapshot(status="定位已丢失").describe("椅子在哪",400).contains("暂不能"))
        assertTrue(SpatialMap.build(samples(),identity(),400).describe("多远",2000).contains("过期"))
    }
    @Test fun historicalObjectReportsEstimatedDistanceAndHeight() {
        val map=SpatialMap.build(samples(Point3(.05f,.7f,-2f)),identity(),2000)
        val answer=map.describe("椅子在哪",2100)
        assertTrue(answer.contains("约2.0米")); assertTrue(answer.contains("可能有椅子")); assertTrue(answer.contains("高于手机"))
        assertTrue(answer.contains("之前的扫描")); assertTrue(answer.contains("仍未知"))
    }
    @Test fun doesNotSubstituteAnotherObjectForUnknownNamedQuery() {
        val map=SpatialMap.build(samples(),identity(),400)
        assertTrue(map.describe("冰箱在哪里",500).contains("没有找到"))
    }
    @Test fun confirmedSemanticObjectKeepsItsOwnMetadataAndDistance() {
        val input=listOf(
            WorldSample(Point3(.1f,0f,-1f),1,100,"椅子",classId=56,confidence=.8f,depthSupport=7,depthSpread=.03f),
            WorldSample(Point3(.1f,0f,-1f),2,300,"椅子",classId=56,confidence=.9f,depthSupport=9,depthSpread=.04f),
        )
        val map=SpatialMap.build(input,identity(),400)
        val item=map.objects.single()
        assertEquals(56,item.classId); assertEquals(.9f,item.confidence,.001f)
        assertEquals(9,item.depthSupport); assertEquals(.04f,item.depthSpread,.001f)
        assertTrue(map.describe("椅子在哪里",500).contains("约1.0米可能有椅子"))
    }
    @Test fun genericDescriptionUsesFusionConfidenceInsteadOfRawLabelledSurface() {
        val medium=samples().map { it.copy(confidence=.6f,depthSupport=6,depthSpread=.15f) }
        val map=SpatialMap.build(medium,identity(),400)
        assertTrue(map.describe("周围有什么",500).contains("可能有椅子"))
        val surfaces=samples(label=null)
        assertTrue(SpatialMap.build(surfaces,identity(),400).describe("周围有什么",500).contains("物体表面"))
    }
    @Test fun verticalPhoneHeadingDisablesDirections() {
        val down=CameraTransform(floatArrayOf(1f,0f,0f,0f, 0f,0f,-1f,0f, 0f,1f,0f,0f, 0f,0f,0f,1f))
        assertFalse(SpatialMap.build(samples(),down,400).usable)
    }
    @Test fun matrixTranslationAndRotationApplyToDepthSample() {
        val transform=CameraTransform(floatArrayOf(0f,0f,1f,0f, 0f,1f,0f,0f, -1f,0f,0f,0f, 1f,2f,3f,1f))
        assertEquals(Point3(3f,2f,3f),transform.world(Point3(0f,0f,-2f)))
    }
}
