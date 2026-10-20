package io.github.bieshuowola.fuelmanager

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.*
import android.webkit.*
import android.widget.*
import androidx.webkit.*
import org.json.JSONObject

class MainActivity:Activity(){
    private lateinit var web:WebView
    private lateinit var repo:LocalRepository
    private var chooser:ValueCallback<Array<Uri>>?=null
    private var exportText:String?=null
    private var pendingPermission:(()->Unit)?=null
    private var ready=false
    private var restoreTracking:JSONObject?=null
    private val handler=Handler(Looper.getMainLooper())
    private val poll=object:Runnable{override fun run(){refresh();handler.postDelayed(this,3000)}}
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState);activityRef=java.lang.ref.WeakReference(this);repo=LocalRepository.get(this)
        if(LocationTrackingService.live==null)repo.recover("process_interruption")
        RideControl.publish(this)
        web=WebView(this)
        val container=FrameLayout(this).apply{setBackgroundColor(Color.rgb(247,248,250));addView(web)}
        container.setOnApplyWindowInsetsListener{v,insets->
            if(Build.VERSION.SDK_INT>=30){val s=insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime());v.setPadding(s.left,s.top,s.right,s.bottom)}
            else v.setPadding(insets.systemWindowInsetLeft,insets.systemWindowInsetTop,insets.systemWindowInsetRight,insets.systemWindowInsetBottom)
            insets
        }
        setContentView(container)
        web.settings.apply{javaScriptEnabled=true;domStorageEnabled=true;allowFileAccess=false;allowContentAccess=true;mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW;setSupportMultipleWindows(false);javaScriptCanOpenWindowsAutomatically=false}
        val loader=WebViewAssetLoader.Builder().addPathHandler("/assets/",WebViewAssetLoader.AssetsPathHandler(this)).build()
        web.webViewClient=object:WebViewClient(){
            override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse? {
                val uri=request.url
                if(uri.scheme=="https" && uri.host=="appassets.androidplatform.net")return loader.shouldInterceptRequest(uri)
                if(uri.scheme=="https" && uri.host=="bieshuowola.github.io" && uri.path=="/today-fuel-price/data/latest.json" && request.method=="GET")return null
                return WebResourceResponse("text/plain","utf-8",403,"Blocked",mapOf(),"Blocked".byteInputStream())
            }
            override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest)=request.url.toString()!=PAGE
            override fun onPageFinished(view:WebView,url:String){if(url==PAGE){ready=true;refresh()}}
        }
        web.webChromeClient=object:WebChromeClient(){
            override fun onJsAlert(view:WebView,url:String,message:String,result:JsResult):Boolean{
                AlertDialog.Builder(this@MainActivity).setMessage(message).setPositiveButton("确定"){_,_->result.confirm()}.setOnCancelListener{result.cancel()}.show();return true
            }
            override fun onJsConfirm(view:WebView,url:String,message:String,result:JsResult):Boolean{
                AlertDialog.Builder(this@MainActivity).setMessage(message).setPositiveButton("确认"){_,_->result.confirm()}.setNegativeButton("取消"){_,_->result.cancel()}.setOnCancelListener{result.cancel()}.show();return true
            }
            override fun onShowFileChooser(view:WebView,callback:ValueCallback<Array<Uri>>,params:FileChooserParams):Boolean{
                chooser?.onReceiveValue(null);chooser=callback
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE),100);return true
            }
        }
        if(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)){
            WebViewCompat.addWebMessageListener(web,"NativeFuel",setOf("https://appassets.androidplatform.net")){_,message,origin,mainFrame,_->
                if(mainFrame && origin.toString()=="https://appassets.androidplatform.net")try{receive(JSONObject(message.data?:"{}"))}catch(e:Exception){tell("操作未完成：${e.javaClass.simpleName}，原记录已保留")}
            }
        } else {tell("请更新 Android System WebView；原网页可用，自动里程桥接暂不可用")}
        web.loadUrl(PAGE)
        if(intent.getBooleanExtra("suggested",false))handler.postDelayed({controls()},800)
    }
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent);if(intent.getBooleanExtra("suggested",false))controls()}
    private fun receive(msg:JSONObject){
        when(msg.optString("type")){
            "fuel"->{
                val fuel=msg.getJSONObject("fuel")
                require(fuel.optInt("version")==1 && fuel.getJSONArray("events").length()<=20000)
                repo.syncFuel(fuel)
                restoreTracking?.let{old->
                    for(key in listOf("trips","calibrations","km","pendingKm","seen","rejected","gaps"))if(old.has(key))repo.state.put(key,old.get(key))
                    repo.state.put("unknown",true).put("active",false).put("reason","restored_backup_requires_odo");repo.commit();restoreTracking=null
                }
                refresh()
            }
            "controls"->controls()
            "beforeImport"->repo.archive()
            "export"->export(msg.getJSONObject("fuel").toString(2),"fuel_manager_backup.json")
            "error"->tell(msg.optString("message"))
        }
    }
    fun refresh(){if(ready)web.evaluateJavascript("window.androidReceive && window.androidReceive(${repo.projection()});",null)}
    private fun permissions(names:Array<String>,done:()->Unit){
        val missing=names.filter{checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED}.toTypedArray()
        if(missing.isEmpty())done() else {pendingPermission=done;requestPermissions(missing,88)}
    }
    override fun onRequestPermissionsResult(code:Int,permissions:Array<out String>,results:IntArray){
        super.onRequestPermissionsResult(code,permissions,results)
        if(code==88){val next=pendingPermission;pendingPermission=null;if(results.isNotEmpty()&&results.all{it==PackageManager.PERMISSION_GRANTED})next?.invoke() else tell("未授权，网页记录与备份仍可使用；精确定位为骑行记录所必需")}
    }
    private fun notificationPermission(done:()->Unit){
        if(Build.VERSION.SDK_INT>=33)permissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),done)else done()
    }
    private fun startRecording(confirmed:Boolean){
        AlertDialog.Builder(this).setTitle(if(confirmed)"记录本次摩托车骑行" else "暂存待确认行程")
            .setMessage("仅在你同意后采集位置，不上传轨迹。锁屏后使用持续通知记录；暂停或定位缺失会要求 ODO 校准。行程静止 10 分钟后结束。请在结束通知中检查行程。")
            .setNegativeButton("取消",null).setPositiveButton("继续"){_,_->
                permissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION)){
                    notificationPermission{
                        try{startForegroundService(Intent(this,LocationTrackingService::class.java).putExtra("confirmed",confirmed))}catch(e:Exception){tell("无法启动定位：${e.javaClass.simpleName}")}
                    }
                }
            }.show()
    }
    private fun controls(){
        val actions=arrayOf("记录本次摩托车骑行","暂存待确认行程","确认待处理行程","结束当前记录","ODO 校准 / 拍照","开启车辆活动提示","关闭自动识别及定位","导出合并备份","导入网页 / 合并备份","运行状态与系统设置","恢复本机安全副本","添加骑行快捷开关")
        AlertDialog.Builder(this).setTitle("骑行与校准").setItems(actions){_,i->when(i){
            0->startRecording(true)
            1->startRecording(false)
            2->{AlertDialog.Builder(this).setTitle("待确认 %.2f km".format(repo.state.optDouble("pendingKm",0.0))).setMessage("活动识别不能区分摩托车、汽车与公交。仅确认属于本车的整个待处理区间；若混有其他交通工具请使用真实 ODO 校准。")
                .setPositiveButton("全部是本车"){_,_->repo.confirmPending(true);refresh()}.setNegativeButton("全部不是本车"){_,_->if(repo.state.optBoolean("active"))stopService(Intent(this,LocationTrackingService::class.java));repo.confirmPending(false);refresh()}.setNeutralButton("取消",null).show()}
            3->{repo.stop();stopService(Intent(this,LocationTrackingService::class.java));refresh()}
            4->calibrate()
            5->{
                AlertDialog.Builder(this).setMessage("低功耗活动转换仅提示疑似车辆活动，不会自动认定是本车，也不会在后台强启 GPS。点通知确认后记录；没有 Google 服务时请用本次骑行记录。")
                    .setNegativeButton("取消",null)
                    .setPositiveButton("授权提示"){_,_->
                        notificationPermission {
                            if(Build.VERSION.SDK_INT>=29) {
                                permissions(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION)) { ActivityMonitoring.enable(this){tell(it)} }
                            } else { ActivityMonitoring.enable(this){tell(it)} }
                        }
                    }.show()
            }
            6->{ActivityMonitoring.disable(this);repo.stop(true);stopService(Intent(this,LocationTrackingService::class.java));refresh();tell("已停止活动监测及定位；未覆盖区间请用 ODO 校准")}
            7->export(repo.backup().toString(2),"fuel_manager_android_backup.json")
            8->startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE),102)
            9->diagnostics()
            10->{
                val files=java.io.File(filesDir,"restore-points").listFiles()?.sortedByDescending{it.lastModified()}?:emptyList()
                if(files.isEmpty())tell("暂无恢复点")else AlertDialog.Builder(this).setTitle("选择操作前保存的副本").setItems(files.map{it.name}.toTypedArray()){_,n->
                    try{val snapshot=JSONObject(files[n].readText());confirmRestore(snapshot.getJSONObject("fuel"),snapshot)}catch(e:Exception){tell("副本无效，未恢复")}
                }.show()
            }
            11->RideTileService.requestAdd(this)
            else->Unit
        }}.setNegativeButton("关闭",null).show()
    }
    private fun calibrate(){
        val options=arrayOf("拍照框选 ODO（本机 OCR）","输入完整 ODO","输入末三位")
        AlertDialog.Builder(this).setTitle("确认真实仪表总里程").setItems(options){_,i->when(i){0->PhotoOdo.capture(this);1->odoInput(false);2->odoInput(true)}}.show()
    }
    fun odoInput(lastThree:Boolean, suggestion:String=""){
        val input=EditText(this).apply{inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL;setText(suggestion);hint=if(lastThree)"三位整数" else "仪表 ODO km"}
        val baseline=repo.state.optJSONObject("fuel")?.optJSONArray("events")?.let{it.optJSONObject(it.length()-1)?.optDouble("odometer")}
        if(baseline==null){tell("请先在原页面确认首次加满，或导入已有网页备份");return}
        val dialog=AlertDialog.Builder(this).setTitle(if(lastThree)"末三位补全（前进不超过 200km）" else "核对仪表 ODO，不是 TRIP")
            .setView(input).setNegativeButton("取消",null).setPositiveButton("下一步",null).create()
        dialog.setOnShowListener{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
            val value=if(lastThree)OdometerCalibration.complete(baseline,input.text.toString())else input.text.toString().toDoubleOrNull()
            if(value==null||!value.isFinite()||value<baseline||value>2000000){input.error="ODO 无效或跨度不明确，请输入完整 ODO";return@setOnClickListener}
            AlertDialog.Builder(this).setTitle("确认真实 ODO %.1f km".format(value)).setMessage("上次 %.1f km，本段真实距离 %.1f km；GPS %.2f km。确认后以真实记录重算并清除重叠预测。请确保此刻读数仍有效。".format(baseline,value-baseline,repo.state.optDouble("km",0.0)+repo.state.optDouble("pendingKm",0.0)))
                .setNegativeButton("取消",null).setPositiveButton("确认校准"){_,_->web.evaluateJavascript("window.androidCalibrate($value)",null);dialog.dismiss()}.show()
        }};dialog.show()
    }
    private fun diagnostics(){
        val text="状态：${repo.state.optString("status","STANDBY")}\n已确认 GPS：%.3f km\n待确认：%.3f km\n缺口：${repo.state.optInt("gaps")}；拒绝定位：${repo.state.optInt("rejected")}\n原因：${repo.state.optString("reason")}\nGoogle 服务：${ActivityMonitoring.available(this)}\n\nvivo / OriginOS：在系统应用设置中允许通知、精确位置与后台高耗电运行，视机型允许自启动。不要清理或强制停止本应用。系统省电仍可能中断记录。重启后请打开应用校准，再开始记录。\n\nChrome / 网页与本 APK 存储独立，先导出 JSON 再导入。合并恢复前会保留 10 份本机恢复点；卸载应用会删除它们。".format(repo.state.optDouble("km",0.0),repo.state.optDouble("pendingKm",0.0))
        AlertDialog.Builder(this).setTitle("记录诊断").setMessage(text).setNegativeButton("关闭",null).setPositiveButton("系统应用设置"){_,_->startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName")))}.show()
    }
    private fun export(text:String,name:String){exportText=text;startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE,name),101)}
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==100){chooser?.onReceiveValue(if(resultCode==RESULT_OK)data?.data?.let{arrayOf(it)}else null);chooser=null;return}
        if(requestCode==200){PhotoOdo.result(this,resultCode);return}
        if(resultCode!=RESULT_OK)return
        val uri=data?.data?:return
        try{when(requestCode){
            101->{exportText?.let{text->contentResolver.openOutputStream(uri)?.use{it.write(text.toByteArray())}};exportText=null;tell("备份已保存")}
            102->{
                val bytes=contentResolver.openInputStream(uri)!!.use{stream->
                    val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                    while(true){val n=stream.read(buffer);if(n<0)break;require(output.size()+n<=8*1024*1024);output.write(buffer,0,n)}
                    output.toByteArray()
                }
                val obj=JSONObject(String(bytes));val tracking=if(obj.optInt("androidBackup")==1)obj.getJSONObject("tracking")else null
                val fuel=tracking?.getJSONObject("fuel")?:obj
                require(fuel.optInt("version")==1&&fuel.has("events"))
                confirmRestore(fuel,tracking)
            }
        }}catch(e:Exception){tell("文件未处理：${e.javaClass.simpleName}；现有记录未替换")}
    }
    private fun confirmRestore(fuel:JSONObject,tracking:JSONObject?){
        AlertDialog.Builder(this).setTitle("备份恢复").setMessage("将覆盖当前燃油账本；操作前自动保留本机恢复点。自动里程恢复后须校准真实 ODO，避免重复扣油。网页原格式仍兼容。")
            .setNegativeButton("取消",null).setPositiveButton("确认恢复"){_,_->
                repo.archive();repo.stop(true);stopService(Intent(this,LocationTrackingService::class.java));restoreTracking=tracking
                web.evaluateJavascript("window.androidImport($fuel)",null)
            }.show()
    }
    private fun tell(text:String){AlertDialog.Builder(this).setMessage(text).setPositiveButton("确定",null).show()}
    override fun onResume(){super.onResume();handler.post(poll)}
    override fun onPause(){handler.removeCallbacks(poll);super.onPause()}
    override fun onDestroy(){handler.removeCallbacksAndMessages(null);chooser?.onReceiveValue(null);web.destroy();if(live===this)activityRef.clear();super.onDestroy()}
    companion object{const val PAGE="https://appassets.androidplatform.net/assets/index.html";private var activityRef=java.lang.ref.WeakReference<MainActivity>(null);val live:MainActivity? get()=activityRef.get()}
}
