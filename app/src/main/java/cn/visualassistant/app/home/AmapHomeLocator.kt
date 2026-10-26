package cn.visualassistant.app.home

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import java.util.concurrent.atomic.AtomicBoolean

object AmapHomeLocator{
    fun locateOnce(context:Context,onResult:(Result<GeoFix>)->Unit){
        val app=context.applicationContext
        val completed=AtomicBoolean(false)
        val handler=Handler(Looper.getMainLooper())
        var client:AMapLocationClient?=null
        fun finish(result:Result<GeoFix>){
            if(!completed.compareAndSet(false,true))return
            handler.removeCallbacksAndMessages(null)
            runCatching{client?.stopLocation();client?.onDestroy()}
            onResult(result)
        }
        runCatching{
            // Must be called before constructing the SDK client.
            AMapLocationClient.updatePrivacyShow(app,true,true)
            AMapLocationClient.updatePrivacyAgree(app,true)
            client=AMapLocationClient(app).apply{
                setLocationListener{location->
                    if(location!=null&&location.errorCode==0&&location.latitude.isFinite()&&location.longitude.isFinite())
                        finish(Result.success(GeoFix(location.latitude,location.longitude,location.accuracy,location.address.orEmpty(),location.time)))
                    else finish(Result.failure(IllegalStateException(location?.let{"高德定位失败 ${it.errorCode}：${it.errorInfo}"}?:"高德未返回定位")))
                }
                setLocationOption(AMapLocationClientOption().apply{
                    locationMode=AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
                    isOnceLocation=true;isOnceLocationLatest=true;isNeedAddress=true
                    isLocationCacheEnable=false;isMockEnable=false;httpTimeOut=12_000
                })
                startLocation()
            }
            handler.postDelayed({finish(Result.failure(IllegalStateException("定位等待超时")))},15_000)
        }.onFailure{finish(Result.failure(it))}
    }
}
