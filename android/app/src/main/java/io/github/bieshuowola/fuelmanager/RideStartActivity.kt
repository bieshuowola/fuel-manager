package io.github.bieshuowola.fuelmanager

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.widget.TextView

/** A visible, short-lived permission/FGS entry. Never depends on WebView or loads the home page. */
class RideStartActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var token: String? = null
    private var waiting = false
    private var requestedStart = false
    private var startedAt = 0L
    private var registered = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!RideControl.isPending(token)) finish()
        }
    }
    private val checkStart = object : Runnable {
        override fun run() {
            if (!RideControl.isPending(token)) { finish(); return }
            if (LocationTrackingService.live?.isRecording == true) {
                RideControl.complete(this@RideStartActivity, token)
                finish(); return
            }
            if (SystemClock.elapsedRealtime() - startedAt >= 8000) {
                RideControl.complete(this@RideStartActivity, token)
                LocationTrackingService.live?.endRide(interrupted = true)
                fail("骑行服务未能启动，未记录的距离需用真实 ODO 校准。请检查定位及后台运行设置。")
            } else handler.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        token = intent.getStringExtra(TOKEN)
        if (!RideControl.isPending(token)) { finish(); return }
        ContextCompat.registerReceiver(this, receiver, IntentFilter(RideControl.CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
        setContentView(TextView(this).apply {
            text = "正在启动骑行…"
            gravity = android.view.Gravity.CENTER
            setPadding(24, 24, 24, 24)
        })
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // A cancelled entry may still be closing; do not reuse it to launch a new hidden command.
        val next = intent.getStringExtra(TOKEN)
        if (next != token) { RideControl.complete(this, next); finish() }
    }
    override fun onResume() {
        super.onResume()
        // Startup is requested only after this Activity has become visible/resumed.
        handler.post { if (!isFinishing && !waiting && !requestedStart) prepare() }
        if (requestedStart) handler.post(checkStart)
    }
    private fun prepare() {
        if (!RideControl.isPending(token)) { finish(); return }
        val repo = LocalRepository.get(this)
        if (!repo.state.optBoolean("rideTileConsent")) {
            waiting = true
            AlertDialog.Builder(this).setTitle("骑行记录")
                .setMessage("仅骑自己的摩托车时开启。开启后使用精确定位与持续通知，在锁屏后继续记录；再次点击「骑行」可结束。不上传位置，缺失距离需真实 ODO 校准。")
                .setPositiveButton("同意并继续") { _, _ ->
                    repo.state.put("rideTileConsent", true); repo.commit(); waiting = false; prepare()
                }.setNegativeButton("取消") { _, _ -> cancel() }.setOnCancelListener { cancel() }.show()
            return
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            waiting = true
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION), 1)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            waiting = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
            return
        }
        if (LocationTrackingService.enabledProvider(this) == null) {
            waiting = true
            AlertDialog.Builder(this).setMessage("定位已关闭或 GNSS 不可用。请打开系统定位后返回，再开始骑行。")
                .setNegativeButton("取消") { _, _ -> cancel() }
                .setPositiveButton("打开定位设置") { _, _ ->
                    try { startActivityForResult(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS), 3) }
                    catch (_: RuntimeException) { cancel() }
                }.setOnCancelListener { cancel() }.show()
            return
        }
        requestedStart = true
        startedAt = SystemClock.elapsedRealtime()
        try {
            startForegroundService(Intent(this, LocationTrackingService::class.java)
                .putExtra("confirmed", true).putExtra(TOKEN, token))
            handler.post(checkStart)
        } catch (e: RuntimeException) {
            RideControl.complete(this, token)
            fail("系统未允许启动骑行（${e.javaClass.simpleName}）。请检查定位权限与后台限制。")
        }
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (isFinishing || !RideControl.isPending(token)) { finish(); return }
        waiting = false
        if (results.isEmpty() || results.any { it != PackageManager.PERMISSION_GRANTED }) {
            RideControl.complete(this, token)
            fail("未取得骑行所需权限，记录未启动。原燃油页面仍可正常使用。")
        } else prepare()
    }
    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        super.onActivityResult(code, result, data)
        if (code == 3) {
            if (isFinishing || !RideControl.isPending(token)) { finish(); return }
            waiting = false
            if (LocationTrackingService.enabledProvider(this) == null) cancel() else prepare()
        }
    }
    private fun cancel() { RideControl.complete(this, token); finish() }
    private fun fail(message: String) {
        waiting = true
        AlertDialog.Builder(this).setMessage(message).setPositiveButton("确定") { _, _ -> finish() }
            .setOnCancelListener { finish() }.show()
    }
    override fun onPause() { handler.removeCallbacks(checkStart); super.onPause() }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (registered) { unregisterReceiver(receiver); registered = false }
        if (!isChangingConfigurations && RideControl.isPending(token)) RideControl.complete(this, token)
        super.onDestroy()
    }
    companion object {
        const val TOKEN = "ride_request_token"
        fun intent(context: Context, token: String): Intent = Intent(context, RideStartActivity::class.java)
            .putExtra(TOKEN, token).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
