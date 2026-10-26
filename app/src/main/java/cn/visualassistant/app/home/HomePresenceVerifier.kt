package cn.visualassistant.app.home

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.InetSocketAddress
import java.net.Socket

data class HomePresenceEvidence(val backendReachable:Boolean,val onWifi:Boolean){
    val candidateHome:Boolean get()=backendReachable
    val description:String get()=when{
        backendReachable&&onWifi->"已连接家庭网络，家庭电脑在线"
        backendReachable->"家庭电脑在线"
        else->"未连接到已登记的家庭电脑"
    }
}

/** Coarse presence only. This must never be used as a room/pose localization result. */
object HomePresenceVerifier{
    fun verify(context:Context,host:String,port:Int,timeoutMs:Int=1200):HomePresenceEvidence{
        // Presence is a convenience hint and must never be allowed to crash the
        // process when an OEM restricts connectivity information.
        val wifi=runCatching{
            val connectivity=context.getSystemService(ConnectivityManager::class.java)
            val network=connectivity?.activeNetwork
            network?.let(connectivity::getNetworkCapabilities)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true
        }.getOrDefault(false)
        val reachable=runCatching{Socket().use{it.connect(InetSocketAddress(host,port),timeoutMs)}}.isSuccess
        return HomePresenceEvidence(reachable,wifi)
    }
}
