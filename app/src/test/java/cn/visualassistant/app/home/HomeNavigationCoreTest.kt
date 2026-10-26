package cn.visualassistant.app.home

import org.junit.Assert.*
import org.junit.Test

class HomeNavigationCoreTest{
    private fun fix(x:Float=0f,yaw:Float=0f,time:Long=1000,inliers:Int=20,error:Float=1f)=LocalizationFix("home",x,0f,0f,yaw,inliers,error,time)
    @Test fun localizationRequiresThreeConsistentGoodFixes(){val gate=HomeLocalizationGate();assertEquals(HomeNavigationState.VERIFYING_HOME,gate.observe(fix()))
        gate.observe(fix(.05f,3f,1100));assertEquals(HomeNavigationState.LOCALIZED_HOME,gate.observe(fix(.08f,5f,1200)));assertNotNull(gate.current(1500))}
    @Test fun staleLocalizationRevokesNavigation(){val gate=HomeLocalizationGate();gate.observe(fix());gate.observe(fix(time=1100));gate.observe(fix(time=1200));assertNull(gate.current(5000));assertEquals(HomeNavigationState.LOCALIZATION_LOST,gate.state)}
    @Test fun badOrJumpingFixCannotLocalize(){val gate=HomeLocalizationGate();gate.observe(fix(inliers=5));gate.observe(fix());gate.observe(fix(1f,time=1100));gate.observe(fix(1.1f,time=1200));assertNotEquals(HomeNavigationState.LOCALIZED_HOME,gate.state)}
    @Test fun destinationOnlyUsesUserConfirmedNames(){val places=listOf(ConfirmedDestination("1","卫生间",setOf("厕所"),true),ConfirmedDestination("2","厨房",userConfirmed=false));assertEquals("1",VoiceDestinationResolver.resolve("我想去厕所",places)?.id);assertNull(VoiceDestinationResolver.resolve("去厨房",places))}
    @Test fun plannerRejectsPreviewAndUnsafeEdges(){val nodes=listOf(RouteNode(0,0f,0f,0f),RouteNode(1,1f,0f,0f));val edge=RouteEdge(0,1,true,.8f,.9f)
        assertNull(HomeRoutePlanner.plan(RouteGraph(false,nodes,listOf(edge)),0,1));assertNull(HomeRoutePlanner.plan(RouteGraph(true,nodes,listOf(edge.copy(verified=false))),0,1))}
    @Test fun plannerReturnsVerifiedRoute(){val nodes=listOf(RouteNode(0,0f,0f,0f),RouteNode(1,1f,0f,0f),RouteNode(2,2f,0f,0f));val edges=listOf(RouteEdge(0,1,true,.8f,.9f),RouteEdge(1,2,true,.8f,.9f));assertEquals(listOf(0,1,2),HomeRoutePlanner.plan(RouteGraph(true,nodes,edges),0,2)?.map{it.id})}
}
