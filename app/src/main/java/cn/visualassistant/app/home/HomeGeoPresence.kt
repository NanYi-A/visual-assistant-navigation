package cn.visualassistant.app.home

import kotlin.math.*

data class GeoFix(val latitude:Double,val longitude:Double,val accuracyMeters:Float,val address:String="",val timeMs:Long=System.currentTimeMillis())
data class HomeGeoAnchor(val latitude:Double,val longitude:Double,val accuracyMeters:Float,val address:String="",val timeMs:Long=System.currentTimeMillis())
data class GeoPresenceDecision(val atHome:Boolean,val distanceMeters:Double,val allowedMeters:Double,val reliable:Boolean){
    val description:String get()=when{
        !reliable->"定位精度不足，无法确认是否在家"
        atHome->"距离家庭位置约${distanceMeters.roundToInt()}米"
        else->"距离家庭位置约${distanceMeters.roundToInt()}米，判定不在家"
    }
}

object HomeGeoPresence{
    fun evaluate(anchor:HomeGeoAnchor,fix:GeoFix):GeoPresenceDecision{
        val distance=haversine(anchor.latitude,anchor.longitude,fix.latitude,fix.longitude)
        val reliable=anchor.accuracyMeters in 1f..100f&&fix.accuracyMeters in 1f..100f
        // Allow measured uncertainty while keeping different buildings apart.
        val allowed=max(35.0,min(100.0,anchor.accuracyMeters+fix.accuracyMeters+15.0))
        return GeoPresenceDecision(reliable&&distance<=allowed,distance,allowed,reliable)
    }
    private fun haversine(aLat:Double,aLon:Double,bLat:Double,bLon:Double):Double{
        val r=6_371_000.0;val p1=Math.toRadians(aLat);val p2=Math.toRadians(bLat)
        val dp=Math.toRadians(bLat-aLat);val dl=Math.toRadians(bLon-aLon)
        val h=sin(dp/2).pow(2)+cos(p1)*cos(p2)*sin(dl/2).pow(2)
        return 2*r*asin(sqrt(h.coerceIn(0.0,1.0)))
    }
}
