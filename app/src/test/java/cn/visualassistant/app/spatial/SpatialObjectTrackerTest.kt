package cn.visualassistant.app.spatial

import cn.visualassistant.app.core.RiskLevel
import org.junit.Assert.*
import org.junit.Test

class SpatialObjectTrackerTest {
    private fun item(world: Point3=Point3(0f,0f,-1f),relative: Point3=world,label: String="椅子",
        age: Long=0, stable: Long=0, quality: FusionConfidence=FusionConfidence.MEDIUM) =
        SpatialObject(56,label,.9f,relative,age,10,.04f,world,stableMs=stable,fusionConfidence=quality)

    @Test fun sameWorldObjectKeepsIdentityWhenPhoneMoves() {
        val tracker=SpatialObjectTracker()
        val first=tracker.update(listOf(item()),1000).single()
        val moved=tracker.update(listOf(item(relative=Point3(-.5f,0f,-1f))),1800).single()
        assertEquals(first.trackId,moved.trackId)
        assertTrue(moved.stableMs>=800)
    }

    @Test fun separatedObjectsOfSameClassKeepDifferentIds() {
        val tracker=SpatialObjectTracker()
        val result=tracker.update(listOf(item(world=Point3(-1f,0f,-1f)),item(world=Point3(1f,0f,-1f))),1000)
        assertEquals(2,result.map { it.trackId }.distinct().size)
    }

    @Test fun expiredTrackGetsANewIdentity() {
        val tracker=SpatialObjectTracker(expiryMs=2000)
        val first=tracker.update(listOf(item()),1000).single().trackId
        tracker.update(emptyList(),3101)
        val second=tracker.update(listOf(item()),3200).single().trackId
        assertNotEquals(first,second)
    }

    @Test fun approachingAndLeavingHaveOppositeVelocitySigns() {
        val tracker=SpatialObjectTracker()
        tracker.update(listOf(item(relative=Point3(0f,0f,-1.5f))),1000)
        val approaching=tracker.update(listOf(item(relative=Point3(0f,0f,-1f))),2000).single()
        assertTrue(approaching.approachMps>0)
        val leaving=tracker.update(listOf(item(relative=Point3(0f,0f,-2f))),3000).single()
        assertTrue(leaving.approachMps<0)
    }

    @Test fun highQualityRequiresStableStrongDepthEvidence() {
        val tracker=SpatialObjectTracker()
        assertEquals(FusionConfidence.MEDIUM,tracker.update(listOf(item()),1000).single().fusionConfidence)
        assertEquals(FusionConfidence.HIGH,tracker.update(listOf(item()),1700).single().fusionConfidence)
    }

    @Test fun centralCloseHighConfidenceObjectIsDangerWithCertainWording() {
        val objectItem=item(relative=Point3(0f,0f,-.6f),stable=1000,quality=FusionConfidence.HIGH)
        val snapshot=SpatialSnapshot(1000,usable=true,objects=listOf(objectItem))
        val risk=SpatialRiskEngine.assess(snapshot,1100)
        assertEquals(RiskLevel.DANGER,risk.level)
        assertTrue(risk.message.contains("约0.6米有椅子"))
        assertFalse(risk.message.contains("可能"))
    }

    @Test fun mediumConfidenceUsesUncertainWordingAndSideObjectIsNotDanger() {
        val objectItem=item(relative=Point3(.8f,0f,-.6f),quality=FusionConfidence.MEDIUM)
        val snapshot=SpatialSnapshot(1000,usable=true,objects=listOf(objectItem))
        val risk=SpatialRiskEngine.assess(snapshot,1100)
        assertNotEquals(RiskLevel.DANGER,risk.level)
        if(risk.level!=RiskLevel.SAFE) assertTrue(risk.message.contains("可能有椅子"))
    }

    @Test fun irrelevantCloserSideObjectDoesNotHideCentralWarning() {
        val side=item(world=Point3(1f,0f,-.4f),relative=Point3(1f,0f,-.4f),label="瓶子")
        val center=item(world=Point3(0f,0f,-1f),relative=Point3(0f,0f,-1f),label="椅子")
        val risk=SpatialRiskEngine.assess(SpatialSnapshot(1000,usable=true,objects=listOf(side,center)),1100)
        assertEquals(RiskLevel.WARNING,risk.level)
        assertTrue(risk.message.contains("椅子"))
    }

    @Test fun unlabeledSurfaceNeverInventsAClass() {
        val snapshot=SpatialSnapshot(1000,marks=listOf(SpatialMark(Point3(0f,0f,-.5f),null,100)),usable=true)
        val risk=SpatialRiskEngine.assess(snapshot,1100)
        assertEquals(RiskLevel.DANGER,risk.level)
        assertTrue(risk.message.contains("物体表面"))
        assertFalse(risk.message.contains("椅子"))
    }
}
