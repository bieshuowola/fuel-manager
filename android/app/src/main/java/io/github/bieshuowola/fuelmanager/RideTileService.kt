package io.github.bieshuowola.fuelmanager

import android.app.Activity
import android.app.PendingIntent
import android.app.StatusBarManager
import android.content.*
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.core.content.ContextCompat

class RideTileService : TileService() {
    private var listening = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { updateState() }
    }

    override fun onStartListening() {
        super.onStartListening()
        RideControl.reconcile(this)
        if (!listening) {
            ContextCompat.registerReceiver(this, receiver, IntentFilter(RideControl.CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            listening = true
        }
        updateState()
    }
    override fun onStopListening() {
        if (listening) { unregisterReceiver(receiver); listening = false }
        super.onStopListening()
    }
    override fun onDestroy() {
        if (listening) { unregisterReceiver(receiver); listening = false }
        super.onDestroy()
    }
    override fun onTileAdded() { super.onTileAdded(); updateState() }
    override fun onClick() {
        super.onClick()
        RideControl.reconcile(this)
        // Stopping is always allowed, including a locked device or a paused session.
        if (LocationTrackingService.live?.hasSession == true) {
            RideControl.cancel(this)
            LocationTrackingService.live?.endRide()
            updateState()
        } else if (RideControl.pendingToken() != null) {
            RideControl.cancel(this) // A second click cancels an unacknowledged start.
            updateState()
        } else if (isLocked) {
            unlockAndRun { launchRide() }
        } else launchRide()
    }

    // Intent overload is used only on API 26–33; PendingIntent overload was added in API 34.
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    private fun launchRide() {
        // Recheck after unlock: another click may already have started a session.
        if (LocationTrackingService.live?.hasSession == true || RideControl.pendingToken() != null) {
            updateState(); return
        }
        val token = RideControl.begin(this)
        val intent = RideStartActivity.intent(this, token)
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 71, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (_: RuntimeException) {
            RideControl.complete(this, token)
            Notifications.suggest(this, "系统未允许快捷启动。请打开燃油管家后开始本次骑行。")
        }
        updateState()
    }

    private fun updateState() {
        val tile = qsTile ?: return
        val service = LocationTrackingService.live
        tile.label = getString(R.string.ride_tile_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_motorcycle)
        tile.state = if (service?.isRecording == true) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 30) {
            tile.subtitle = ""
            tile.stateDescription = when {
                service?.isRecording == true -> "正在记录骑行"
                service?.hasSession == true -> "已暂停，点按结束"
                RideControl.pendingToken() != null -> "正在启动，点按取消"
                else -> "未记录"
            }
        }
        tile.updateTile()
    }

    companion object {
        fun requestAdd(activity: Activity) {
            if (Build.VERSION.SDK_INT >= 33) {
                try {
                    activity.getSystemService(StatusBarManager::class.java).requestAddTileService(
                        ComponentName(activity, RideTileService::class.java), activity.getString(R.string.ride_tile_label),
                        Icon.createWithResource(activity, R.drawable.ic_motorcycle), activity.mainExecutor
                    ) { result ->
                        val text = when (result) {
                            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "已添加骑行快捷开关"
                            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "骑行快捷开关已添加"
                            else -> "可在控制中心的编辑入口手动添加「骑行」"
                        }
                        Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
                    }
                } catch (_: RuntimeException) { instructions(activity) }
            } else instructions(activity)
        }
        private fun instructions(activity: Activity) {
            Toast.makeText(activity, "下拉系统快捷设置，进入编辑，将「骑行」拖入常用区域", Toast.LENGTH_LONG).show()
        }
    }
}
