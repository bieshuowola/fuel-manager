package io.github.bieshuowola.fuelmanager

import android.Manifest
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowTileService
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], shadows=[RideTileTest.ModernTileShadow::class])
class RideTileTest {
    private lateinit var context:Context
    private lateinit var repo:LocalRepository
    private lateinit var tile:RideTileService
    private val services=mutableListOf<org.robolectric.android.controller.ServiceController<LocationTrackingService>>()

    @Implements(TileService::class)
    class ModernTileShadow:ShadowTileService() {
        // Robolectric 4.15 TileService shadow has no ShadowService parent; its native
        // super.onDestroy() otherwise casts to an incompatible shadow. No SystemUI binder is attached here.
        @Implementation protected fun onDestroy() {}
        var launched:PendingIntent?=null
        @Implementation protected fun startActivityAndCollapse(intent:PendingIntent){launched=intent}
    }
    @Before fun setup(){
        context=RuntimeEnvironment.getApplication()
        ReflectionHelpers.setStaticField(LocalRepository::class.java,"instance",null)
        repo=LocalRepository.get(context)
        repo.state.keys().asSequence().toList().forEach{repo.state.remove(it)}
        repo.state.put("schema",1);repo.commit()
        RideControl.cancel(context)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.POST_NOTIFICATIONS)
        val manager=context.getSystemService(LocationManager::class.java)
        shadowOf(manager).setLocationEnabled(true)
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER,true)
        shadowOf(manager).setProviderEnabled(LocationManager.FUSED_PROVIDER,false)
        tile=Robolectric.buildService(RideTileService::class.java).create().get()
        tile.onStartListening()
    }
    @After fun cleanup(){
        services.forEach{it.destroy()}
        tile.onStopListening();tile.onDestroy();RideControl.cancel(context)
    }
    private fun service():LocationTrackingService {
        val controller=Robolectric.buildService(LocationTrackingService::class.java).create()
        services.add(controller);return controller.get()
    }
    private fun launch(service:LocationTrackingService,token:String?=null){
        val intent=Intent(context,LocationTrackingService::class.java).putExtra("confirmed",true)
        if(token!=null)intent.putExtra(RideStartActivity.TOKEN,token)
        service.onStartCommand(intent,0,1)
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun sync(){shadowOf(Looper.getMainLooper()).idle();tile.onStartListening()}
    @Test fun labelIconAndManifestAreRealSystemTile(){
        assertEquals("骑行",tile.qsTile.label.toString());assertNotNull(tile.qsTile.icon)
        val info=context.packageManager.getServiceInfo(ComponentName(context,RideTileService::class.java),PackageManager.GET_META_DATA)
        assertEquals(Manifest.permission.BIND_QUICK_SETTINGS_TILE,info.permission);assertTrue(info.exported)
        assertTrue(info.metaData.getBoolean(TileService.META_DATA_TOGGLEABLE_TILE))
        val longPress=Intent(TileService.ACTION_QS_TILE_PREFERENCES).setPackage(context.packageName)
        assertEquals(MainActivity::class.java.name,context.packageManager.resolveActivity(longPress,PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.name)
    }
    @Test fun defaultOffAndPendingStartNeverHighlights(){
        assertEquals(Tile.STATE_INACTIVE,tile.qsTile.state)
        tile.onClick()
        assertNotNull(RideControl.pendingToken());assertEquals(Tile.STATE_INACTIVE,tile.qsTile.state)
        val pending=(shadowOf(tile) as ModernTileShadow).launched!!
        assertEquals(RideStartActivity::class.java.name,shadowOf(pending).savedIntent.component?.className)
        assertNull(LocationTrackingService.live)
    }
    @Test fun highlightOnlyAfterRealLocationSubscriptionThenClickStopsOnce(){
        val service=service();val token=RideControl.begin(context);launch(service,token);sync()
        assertTrue(service.isRecording);assertEquals(Tile.STATE_ACTIVE,tile.qsTile.state)
        assertEquals(1,shadowOf(context.getSystemService(LocationManager::class.java)).locationUpdateListeners.size)
        repo.distance(DistanceResult(1250.0,true),SystemClock.elapsedRealtime())
        tile.onClick();sync()
        assertFalse(service.isRecording);assertEquals(Tile.STATE_INACTIVE,tile.qsTile.state)
        assertEquals(1,repo.state.getJSONArray("trips").length());assertEquals(1.25,repo.state.getDouble("km"),0.0)
        service.onStartCommand(Intent(context,LocationTrackingService::class.java).setAction("STOP"),0,2)
        assertEquals(1,repo.state.getJSONArray("trips").length())
        assertTrue(shadowOf(context.getSystemService(LocationManager::class.java)).locationUpdateListeners.isEmpty())
    }
    @Test fun rapidDoubleClickCancelsAndDelayedCommandCannotStartGps(){
        tile.onClick();val cancelled=RideControl.pendingToken()!!;tile.onClick()
        assertNull(RideControl.pendingToken())
        val service=service();launch(service,cancelled);sync()
        assertFalse(service.isRecording);assertFalse(repo.state.optBoolean("active"))
        assertEquals(Tile.STATE_INACTIVE,tile.qsTile.state)
    }
    @Test fun duplicateStartKeepsOneListenerAndOneSession(){
        val service=service();val token=RideControl.begin(context);launch(service,token)
        launch(service,token);launch(service)
        assertEquals(1,shadowOf(context.getSystemService(LocationManager::class.java)).locationUpdateListeners.size)
        assertEquals(0.0,repo.state.getDouble("sessionKm"),0.0)
    }
    @Test fun pauseDoesNotPretendGpsIsRecordingAndTileEndsPausedSession(){
        val service=service();launch(service)
        service.onStartCommand(Intent(context,LocationTrackingService::class.java).setAction("PAUSE"),0,2);sync()
        assertTrue(service.hasSession);assertFalse(service.isRecording);assertEquals(Tile.STATE_INACTIVE,tile.qsTile.state)
        tile.onClick();assertFalse(service.hasSession)
    }
    @Test fun revokedPermissionAndDisabledProviderNeverHighlight(){
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val service=service();launch(service);sync()
        assertFalse(service.isRecording);assertEquals(Tile.STATE_INACTIVE,tile.qsTile.state)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(context.getSystemService(LocationManager::class.java)).setProviderEnabled(LocationManager.GPS_PROVIDER,false)
        launch(service);sync();assertFalse(service.isRecording)
    }
    @Test fun systemForegroundStartRejectionCannotHighlightOrLeavePendingRequest(){
        val service=service()
        shadowOf(service).setThrowInStartForeground(android.app.ForegroundServiceStartNotAllowedException("blocked by system"))
        val token=RideControl.begin(context);launch(service,token);sync()
        assertFalse(service.isRecording);assertNull(RideControl.pendingToken())
        assertTrue(repo.state.getBoolean("unknown"));assertEquals(Tile.STATE_INACTIVE,tile.qsTile.state)
        assertTrue(shadowOf(context.getSystemService(LocationManager::class.java)).locationUpdateListeners.isEmpty())
    }
    @Test fun permissionRevokedWhileRecordingImmediatelyClearsTileOnListening(){
        val service=service();launch(service)
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        sync();assertFalse(service.isRecording);assertFalse(service.hasSession)
        assertTrue(repo.state.getBoolean("unknown"));assertEquals(Tile.STATE_INACTIVE,tile.qsTile.state)
    }
    @Test fun lateDisabledProviderCallbackCannotRestartPausedGps(){
        val service=service();launch(service)
        service.onStartCommand(Intent(context,LocationTrackingService::class.java).setAction("PAUSE"),0,2)
        service.onProviderDisabled(LocationManager.GPS_PROVIDER)
        assertFalse(service.isRecording);assertTrue(shadowOf(context.getSystemService(LocationManager::class.java)).locationUpdateListeners.isEmpty())
    }
    @Test fun processLossAndBootCloseSavedSessionWithoutRestart(){
        repo.start(true);repo.distance(DistanceResult(1000.0,true),10)
        tile.onStartListening()
        assertFalse(repo.state.optBoolean("active"));assertTrue(repo.state.getBoolean("unknown"))
        assertEquals(1,repo.state.getJSONArray("trips").length());assertEquals(Tile.STATE_INACTIVE,tile.qsTile.state)
        RecoveryReceiver().onReceive(context,Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(1,repo.state.getJSONArray("trips").length());assertNull(LocationTrackingService.live)
    }
    @Test fun unexpectedDestructionMarksInterruptedAndClearsTile(){
        val service=service();launch(service);service.onDestroy();sync()
        assertEquals(Tile.STATE_INACTIVE,tile.qsTile.state);assertTrue(repo.state.getBoolean("unknown"))
        assertTrue(repo.state.getJSONArray("trips").getJSONObject(0).getBoolean("interrupted"))
    }
    @Test fun redLightKeepsSessionAndLongStationaryEvidenceEndsIt(){
        val service=service();launch(service)
        fun stationary(){
            val fix=Location(LocationManager.GPS_PROVIDER).apply{latitude=26.66;longitude=119.55;accuracy=3f;speed=0f;elapsedRealtimeNanos=SystemClock.elapsedRealtimeNanos()}
            service.onLocationChanged(fix)
        }
        for(i in 1..20){shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));stationary()}
        assertTrue(service.isRecording);assertEquals("SHORT_STOP",repo.state.getString("status"))
        for(i in 1..55){shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10));stationary()}
        sync();assertFalse(service.hasSession);assertEquals(Tile.STATE_INACTIVE,tile.qsTile.state)
        assertEquals(1,repo.state.getJSONArray("trips").length())
    }
    @Test fun staleNotificationResumeCannotResurrectStoppedTrip(){
        val service=service();launch(service);service.endRide()
        service.onStartCommand(Intent(context,LocationTrackingService::class.java).setAction("RESUME"),0,3)
        assertFalse(service.isRecording);assertTrue(shadowOf(context.getSystemService(LocationManager::class.java)).locationUpdateListeners.isEmpty())
    }
    @Test fun visiblePermissionEntryStartsNoWebViewAndWaitsForServiceAck(){
        repo.state.put("rideTileConsent",true);repo.commit()
        val token=RideControl.begin(context)
        val controller=Robolectric.buildActivity(RideStartActivity::class.java,RideStartActivity.intent(context,token)).create()
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
        controller.start().resume().visible();shadowOf(Looper.getMainLooper()).idle()
        val command=shadowOf(RuntimeEnvironment.getApplication()).nextStartedService!!
        assertEquals(LocationTrackingService::class.java.name,command.component!!.className)
        assertTrue(command.getBooleanExtra("confirmed",false));assertEquals(token,command.getStringExtra(RideStartActivity.TOKEN))
        assertNull(MainActivity.live);assertEquals(Tile.STATE_INACTIVE,tile.qsTile.state)
        val service=service();service.onStartCommand(command,0,1);sync()
        assertEquals(Tile.STATE_ACTIVE,tile.qsTile.state);assertTrue(controller.get().isFinishing)
        controller.pause().stop().destroy()
    }
    @Test fun deniedPermissionEntryDoesNotStartOrChangeFuelLedger(){
        repo.state.put("rideTileConsent",true);repo.commit()
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val before=repo.state.optJSONObject("fuel")?.toString()
        val controller=Robolectric.buildActivity(RideStartActivity::class.java,RideStartActivity.intent(context,RideControl.begin(context))).create().start().resume().visible()
        shadowOf(Looper.getMainLooper()).idle()
        controller.get().onRequestPermissionsResult(1,arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),intArrayOf(PackageManager.PERMISSION_DENIED))
        assertNull(RideControl.pendingToken());assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
        assertEquals(before,repo.state.optJSONObject("fuel")?.toString());sync();assertEquals(Tile.STATE_INACTIVE,tile.qsTile.state)
        controller.pause().stop().destroy()
    }
    @Test fun oldServiceDestructionCannotCancelANewerClick(){
        val service=service();launch(service);service.endRide()
        val next=RideControl.begin(context)
        service.onDestroy()
        assertTrue(RideControl.isPending(next))
    }
    @Test fun oldRequestCannotCompleteNewRequest(){
        val previous=RideControl.begin(context);RideControl.cancel(context);val next=RideControl.begin(context)
        RideControl.complete(context,previous)
        assertTrue(RideControl.isPending(next));assertNotEquals(previous,next)
    }
    @Test fun lockedClickRequestsUnlockAndStillWaitsForVisibleEntry(){
        (shadowOf(tile) as ModernTileShadow).setLocked(true);tile.onClick()
        assertNotNull((shadowOf(tile) as ModernTileShadow).launched)
        assertEquals(Tile.STATE_INACTIVE,tile.qsTile.state);assertNull(LocationTrackingService.live)
    }
}
