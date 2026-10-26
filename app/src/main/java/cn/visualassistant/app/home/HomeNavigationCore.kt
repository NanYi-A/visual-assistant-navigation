package cn.visualassistant.app.home

import kotlin.math.*

enum class HomeNavigationState { SEARCHING_HOME, VERIFYING_HOME, LOCALIZED_HOME, NAVIGATING, NOT_HOME, LOCALIZATION_LOST }

data class LocalizationFix(val modelId:String,val x:Float,val y:Float,val z:Float,val yawDegrees:Float,
    val inliers:Int,val reprojectionErrorPx:Float,val timestampMs:Long)

/** Safety gate between visual matching and every route instruction. */
class HomeLocalizationGate(private val requiredFixes:Int=3,private val maxAgeMs:Long=3_000){
    var state=HomeNavigationState.SEARCHING_HOME;private set
    var accepted:LocalizationFix?=null;private set
    private val recent=ArrayDeque<LocalizationFix>()
    fun observe(fix:LocalizationFix):HomeNavigationState{
        if(fix.inliers<12||fix.reprojectionErrorPx>3f){recent.clear();state=HomeNavigationState.VERIFYING_HOME;return state}
        if(recent.firstOrNull()?.modelId!=null&&recent.first().modelId!=fix.modelId)recent.clear()
        recent.addLast(fix);while(recent.size>requiredFixes)recent.removeFirst()
        state=HomeNavigationState.VERIFYING_HOME
        if(recent.size==requiredFixes){
            val anchor=recent.first();val consistent=recent.all{distance(anchor,it)<=.30f&&angleDifference(anchor.yawDegrees,it.yawDegrees)<=10f}
            if(consistent){accepted=fix;state=HomeNavigationState.LOCALIZED_HOME}else recent.removeFirst()
        }
        return state
    }
    fun current(nowMs:Long):LocalizationFix?{
        val fix=accepted
        if(fix==null||nowMs-fix.timestampMs>maxAgeMs){if(fix!=null)state=HomeNavigationState.LOCALIZATION_LOST;return null}
        return fix
    }
    fun beginNavigation(nowMs:Long):Boolean{if(current(nowMs)==null)return false;state=HomeNavigationState.NAVIGATING;return true}
    fun reset(){recent.clear();accepted=null;state=HomeNavigationState.SEARCHING_HOME}
    private fun distance(a:LocalizationFix,b:LocalizationFix)=sqrt((a.x-b.x).pow(2)+(a.y-b.y).pow(2)+(a.z-b.z).pow(2))
    private fun angleDifference(a:Float,b:Float)=abs((a-b+540f)%360f-180f)
}

data class ConfirmedDestination(val id:String,val name:String,val aliases:Set<String> = emptySet(),val userConfirmed:Boolean)

object VoiceDestinationResolver{
    fun resolve(utterance:String,places:List<ConfirmedDestination>):ConfirmedDestination?{
        val candidates=places.filter{it.userConfirmed}.flatMap{place->(place.aliases+place.name).filter{it.isNotBlank()}.map{it to place}}
            .filter{(name,_)->utterance.contains(name)}.sortedByDescending{it.first.length}
        return candidates.firstOrNull()?.second
    }
}

data class RouteNode(val id:Int,val x:Float,val y:Float,val z:Float)
data class RouteEdge(val from:Int,val to:Int,val verified:Boolean,val widthMeters:Float,val confidence:Float)
data class RouteGraph(val safeForNavigation:Boolean,val nodes:List<RouteNode>,val edges:List<RouteEdge>)

/** Dijkstra over verified edges only; an engineering preview graph can never produce directions. */
object HomeRoutePlanner{
    fun plan(graph:RouteGraph,start:Int,goal:Int):List<RouteNode>?{
        if(!graph.safeForNavigation)return null
        val byId=graph.nodes.associateBy{it.id};if(start !in byId||goal !in byId)return null
        val edges=graph.edges.filter{it.verified&&it.widthMeters>=.45f&&it.confidence>=.7f}.flatMap{listOf(it,it.copy(from=it.to,to=it.from))}.groupBy{it.from}
        val distance=mutableMapOf(start to 0f);val previous=mutableMapOf<Int,Int>();val pending=graph.nodes.map{it.id}.toMutableSet()
        while(pending.isNotEmpty()){
            val current=pending.minByOrNull{distance[it]?:Float.POSITIVE_INFINITY}?:break
            val base=distance[current]?:break;pending.remove(current);if(current==goal)break
            for(edge in edges[current].orEmpty())if(edge.to in pending){val a=byId.getValue(current);val b=byId.getValue(edge.to)
                val length=sqrt((a.x-b.x).pow(2)+(a.y-b.y).pow(2)+(a.z-b.z).pow(2));val cost=base+length+(1-edge.confidence)*2f
                if(cost<(distance[edge.to]?:Float.POSITIVE_INFINITY)){distance[edge.to]=cost;previous[edge.to]=current}}
        }
        if(goal !in distance)return null;val ids=mutableListOf(goal);while(ids.last()!=start)ids+=previous[ids.last()]?:return null
        return ids.asReversed().map(byId::getValue)
    }
}
