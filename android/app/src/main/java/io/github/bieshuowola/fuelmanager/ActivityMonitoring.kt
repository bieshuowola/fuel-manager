package io.github.bieshuowola.fuelmanager

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.location.*

object Notifications {
    private fun init(c:Context):NotificationManager=c.getSystemService(NotificationManager::class.java).also {
        it.createNotificationChannel(NotificationChannel("tracking","骑行记录",NotificationManager.IMPORTANCE_LOW))
        it.createNotificationChannel(NotificationChannel("suggestions","行程确认及恢复",NotificationManager.IMPORTANCE_DEFAULT))
    }
    private fun open(c:Context)=PendingIntent.getActivity(c,1,Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun action(c:Context,name:String)=PendingIntent.getService(c,name.hashCode(),Intent(c,LocationTrackingService::class.java).setAction(name),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    fun recording(c:Context,text:String):Notification {
        init(c)
        return Notification.Builder(c,"tracking").setSmallIcon(android.R.drawable.ic_menu_compass).setContentTitle("燃油管家 · 正在记录")
            .setContentText(text).setContentIntent(open(c)).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null,"暂停",action(c,"PAUSE")).build())
            .addAction(Notification.Action.Builder(null,"继续",action(c,"RESUME")).build())
            .addAction(Notification.Action.Builder(null,"结束",action(c,"STOP")).build()).build()
    }
    fun suggest(c:Context,text:String){
        val nm=init(c)
        if(Build.VERSION.SDK_INT>=33 && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return
        val intent=PendingIntent.getActivity(c,2,Intent(c,MainActivity::class.java).putExtra("suggested",true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        nm.notify(8,Notification.Builder(c,"suggestions").setSmallIcon(android.R.drawable.ic_menu_compass).setContentTitle("燃油管家 · 请确认行程")
            .setContentText(text).setContentIntent(intent).setAutoCancel(true).addAction(Notification.Action.Builder(null,"确认并记录",intent).build()).build())
    }
}
object ActivityMonitoring {
    private fun pending(c:Context)=PendingIntent.getBroadcast(c,42,Intent(c,ActivityReceiver::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
    fun available(c:Context)=GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(c)==ConnectionResult.SUCCESS
    @Suppress("MissingPermission")
    fun enable(c:Context,onResult:(String)->Unit) {
        if(!available(c)){onResult("此设备无可用 Google Play services；请使用本次摩托车记录，GNSS 不受影响");return}
        if(Build.VERSION.SDK_INT>=29 && c.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION)!=PackageManager.PERMISSION_GRANTED){onResult("缺少活动识别授权");return}
        val transitions=listOf(ActivityTransition.ACTIVITY_TRANSITION_ENTER,ActivityTransition.ACTIVITY_TRANSITION_EXIT).map {
            ActivityTransition.Builder().setActivityType(DetectedActivity.IN_VEHICLE).setActivityTransition(it).build()
        }
        ActivityRecognition.getClient(c).requestActivityTransitionUpdates(ActivityTransitionRequest(transitions),pending(c))
            .addOnSuccessListener{LocalRepository.get(c).state.put("monitoring",true);LocalRepository.get(c).commit();onResult("已开启低功耗车辆活动提示。无法辨别汽车与摩托车，请在通知中确认并开始定位。")}
            .addOnFailureListener{onResult("活动识别不可用：${it.javaClass.simpleName}；可手动记录")}
    }
    fun disable(c:Context){
        val repo=LocalRepository.get(c);repo.state.put("monitoring",false);repo.commit()
        try { if(available(c))ActivityRecognition.getClient(c).removeActivityTransitionUpdates(pending(c)) } catch(_:SecurityException) { /* Already revoked; cancel the PendingIntent below too. */ }
        pending(c).cancel()
        c.getSystemService(NotificationManager::class.java).cancel(8)
    }
}
class ActivityReceiver:BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent){
        val repo=LocalRepository.get(context)
        if(!repo.state.optBoolean("monitoring")||!ActivityTransitionResult.hasResult(intent))return
        val events=ActivityTransitionResult.extractResult(intent)?.transitionEvents?:return
        if(events.any{it.activityType==DetectedActivity.IN_VEHICLE && it.transitionType==ActivityTransition.ACTIVITY_TRANSITION_ENTER}){
            val now=SystemClock.elapsedRealtime();val previous=repo.state.optLong("lastSuggestion",-600000)
            if(now>=previous && now-previous<300000)return
            repo.state.put("lastSuggestion",now);repo.commit()
            Notifications.suggest(context,"检测到车辆活动，尚未记录距离。若正在骑本车，请打开并确认；汽车或公交请忽略。")
        }
    }
}
class RecoveryReceiver:BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent){
        if(intent.action!=Intent.ACTION_BOOT_COMPLETED && intent.action!=Intent.ACTION_MY_PACKAGE_REPLACED)return
        val repo=LocalRepository.get(context)
        repo.recover("boot_or_upgrade")
        if(repo.state.optBoolean("monitoring")){
            repo.state.put("unknown",true);repo.commit()
            ActivityMonitoring.enable(context){}
            Notifications.suggest(context,"手机重启或应用升级，未记录的区间需要 ODO 校准。打开后可恢复记录。")
        }
    }
}
