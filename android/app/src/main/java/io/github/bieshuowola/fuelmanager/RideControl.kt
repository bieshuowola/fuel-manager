package io.github.bieshuowola.fuelmanager

import android.Manifest
import android.content.pm.PackageManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.quicksettings.TileService
import java.util.UUID

/** Main-thread command gate. A pending click never counts as a running recording. */
object RideControl {
    const val CHANGED = "io.github.bieshuowola.fuelmanager.RIDE_CHANGED"
    private var pending: String? = null

    fun pendingToken(): String? = pending
    fun isPending(token: String?): Boolean = token != null && pending == token
    fun begin(context: Context): String {
        pending?.let { return it }
        return UUID.randomUUID().toString().also { pending = it; publish(context) }
    }
    fun complete(context: Context, token: String?) {
        if (isPending(token)) { pending = null; publish(context) }
    }
    fun cancel(context: Context) {
        pending = null
        publish(context)
    }
    fun reconcile(context: Context) {
        val service = LocationTrackingService.live
        if (service == null) {
            LocalRepository.get(context).recover("service_not_running")
        } else if (service.hasSession && context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            service.endRide(interrupted = true)
        }
    }
    fun publish(context: Context) {
        context.sendBroadcast(Intent(CHANGED).setPackage(context.packageName))
        try {
            TileService.requestListeningState(context, ComponentName(context, RideTileService::class.java))
        } catch (_: RuntimeException) { /* Tile not installed / OEM SystemUI unavailable. */ }
    }
}
