package io.github.bieshuowola.fuelmanager

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.*
import android.os.*

class LocationTrackingService:Service(),LocationListener {
    private lateinit var repo:LocalRepository
    private lateinit var manager:LocationManager
    private val accumulator=DistanceAccumulator()
    private val detector=TripDetector()
    private val handler=Handler(Looper.getMainLooper())
    private var lastFix=0L
    private var interval=3000L
    private var paused=false
    private val watchdog=object:Runnable {
        override fun run(){
            if(!paused && repo.state.optBoolean("active")){
                if(SystemClock.elapsedRealtime()-lastFix>300000){stopRecording(true);Notifications.suggest(this@LocationTrackingService,"定位持续中断，记录已停止。请校准真实 ODO 后恢复。");return}
                if(SystemClock.elapsedRealtime()-lastFix>45000){repo.state.put("unknown",true).put("reason","no_fixes_45s");repo.commit();publish()}
                if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){stopRecording(true);return}
            }
            handler.postDelayed(this,15000)
        }
    }
    override fun onCreate(){
        super.onCreate();repo=LocalRepository.get(this);repo.recover("service_recreated");live=this
        manager=getSystemService(LocationManager::class.java);lastFix=SystemClock.elapsedRealtime()
        handler.postDelayed(watchdog,15000)
    }
    override fun onBind(intent:Intent?)=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        when(intent?.action){
            "STOP"->{stopRecording();return START_NOT_STICKY}
            "PAUSE"->{paused=true;manager.removeUpdates(this);repo.state.put("unknown",true).put("status","SHORT_STOP").put("reason","user_pause");repo.commit();publish();return START_NOT_STICKY}
            "RESUME"->{paused=false;reanchor();requestLocations();return START_NOT_STICKY}
        }
        if(repo.state.optBoolean("active")){publish();return START_NOT_STICKY}
        try {
            val notification=Notifications.recording(this,"正在建立定位，保持持续通知")
            if(Build.VERSION.SDK_INT>=29)startForeground(7,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) else startForeground(7,notification)
            if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)throw SecurityException("precise location missing")
            repo.start(intent?.getBooleanExtra("confirmed",false)==true)
            detector.start(repo.state.optBoolean("confirmed"),SystemClock.elapsedRealtime())
            reanchor();requestLocations();publish()
        }catch(e:Exception){repo.state.put("unknown",true).put("reason",e.javaClass.simpleName);repo.commit();stopRecording(true)}
        return START_NOT_STICKY
    }
    @Suppress("MissingPermission")
    private fun requestLocations(){
        try {
            manager.removeUpdates(this)
            val provider=if(Build.VERSION.SDK_INT>=31 && manager.hasProvider(LocationManager.FUSED_PROVIDER) && manager.isProviderEnabled(LocationManager.FUSED_PROVIDER))LocationManager.FUSED_PROVIDER else LocationManager.GPS_PROVIDER
            if(!manager.isProviderEnabled(provider))throw IllegalStateException("location disabled")
            manager.requestLocationUpdates(provider,interval,0f,this,Looper.getMainLooper())
        }catch(e:Exception){repo.state.put("unknown",true).put("status","LOCATION_ERROR").put("reason",e.javaClass.simpleName);repo.commit();publish()}
    }
    override fun onLocationChanged(location:Location){
        if(paused||!repo.state.optBoolean("active"))return
        val now=SystemClock.elapsedRealtime()
        val offered=if(location.isFromMockProvider) DistanceResult(gap=true,reason="mock_location") else accumulator.offer(Fix(location.latitude,location.longitude,if(location.hasAccuracy())location.accuracy.toDouble() else 999.0,location.elapsedRealtimeNanos/1000000,if(location.hasSpeed())location.speed.toDouble() else null),now)
        val result=if(offered.reason=="anchor" && (!location.hasSpeed() || location.speed>0.65f)) offered.copy(gap=true,reason="moving_before_anchor") else offered
        if(result.accepted){lastFix=now;repo.state.put("acquiring",false)}
        repo.distance(result,now)
        val status=detector.observe(now,result.metres,if(location.hasSpeed())location.speed.toDouble() else null)
        repo.state.put("status",if(result.gap)"LOCATION_ERROR" else status.name);repo.commit()
        if(status==TripState.ENDED){stopRecording();return}
        val desired=if(status==TripState.SHORT_STOP)10000L else 3000L
        if(interval!=desired){interval=desired;requestLocations()}
        publish()
    }
    override fun onProviderDisabled(provider:String){repo.state.put("unknown",true).put("reason","provider_disabled");repo.commit();publish();requestLocations()}
    fun reanchor(){val now=SystemClock.elapsedRealtime();accumulator.reanchor(now);lastFix=now;repo.state.put("acquiring",true);repo.commit()}
    private fun publish(){
        getSystemService(NotificationManager::class.java).notify(7,Notifications.recording(this,
            if(repo.projection().optBoolean("unknown"))"里程不可靠 / 待确认，请校准 ODO" else "${repo.state.optString("status")} · GPS %.2f km".format(repo.state.optDouble("km",0.0))))
        MainActivity.live?.refresh()
    }
    private fun stopRecording(interrupted:Boolean=false){
        manager.removeUpdates(this);repo.stop(interrupted);MainActivity.live?.refresh()
        stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
    }
    override fun onDestroy(){handler.removeCallbacksAndMessages(null);manager.removeUpdates(this);repo.stop(true);live=null;super.onDestroy()}
    companion object {var live:LocationTrackingService?=null;private set}
}
