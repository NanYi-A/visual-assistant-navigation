package cn.visualassistant.app.spatial

import cn.visualassistant.app.core.Detection
import cn.visualassistant.app.core.NormalizedBox
import org.junit.Assert.*
import org.junit.Test

class SemanticDepthFusionTest {
    private fun detection(label: String, left: Float, top: Float, right: Float, bottom: Float, id: Int = 1) =
        Detection(id, label, .9f, NormalizedBox(left, top, right, bottom))
    private fun samples(u: Float, v: Float, z: Float, count: Int = 6) =
        List(count) { i -> DepthImageSample(Point3((i-2)*.01f, 0f, z-i*.005f), u, v) }

    @Test fun allFourRotationsMapDepthCoordinatesIntoDetectorSpace() {
        val source=DepthImageSample(Point3(0f,0f,-1f),.2f,.3f)
        assertEquals(.2f to .3f,SemanticDepthFusion.detectorCoordinates(source,0))
        assertEquals(.7f to .2f,SemanticDepthFusion.detectorCoordinates(source,90))
        assertEquals(.8f to .7f,SemanticDepthFusion.detectorCoordinates(source,180))
        assertEquals(.3f to .8f,SemanticDepthFusion.detectorCoordinates(source,270))
    }

    @Test fun twoBoxesKeepTheirOwnLabelsAndDepths() {
        val detections=listOf(detection("椅子",.05f,.3f,.35f,.7f),detection("瓶子",.65f,.3f,.95f,.7f,2))
        val depth=samples(.2f,.5f,-.7f)+samples(.8f,.5f,-2f)
        val fused=SemanticDepthFusion.locate(detections,depth,0).associateBy { it.label }
        assertEquals(-.71f,fused.getValue("椅子").point.z,.04f)
        assertEquals(-2.01f,fused.getValue("瓶子").point.z,.04f)
    }

    @Test fun insufficientOrMixedDepthDoesNotCreateObject() {
        val item=detection("椅子",0f,0f,1f,1f)
        assertTrue(SemanticDepthFusion.locate(listOf(item),samples(.5f,.5f,-1f,4),0).isEmpty())
        val mixed=samples(.5f,.5f,-.5f,3)+samples(.5f,.5f,-2f,3)
        assertTrue(SemanticDepthFusion.locate(listOf(item),mixed,0).isEmpty())
    }

    @Test fun hazardNameMustBeSpatiallyAdjacent() {
        val surface=SpatialMark(Point3(0f,0f,-.8f),null,100)
        val chair=SpatialObject(1,"椅子",.9f,Point3(.1f,0f,-.85f),100,8,.02f)
        val farBottle=SpatialObject(2,"瓶子",.9f,Point3(1f,0f,-2f),100,8,.02f)
        assertEquals("椅子",FusedHazardSelector.objectNear(surface,listOf(farBottle,chair))?.label)
        assertNull(FusedHazardSelector.objectNear(surface,listOf(farBottle)))
    }
}
