package io.github.bieshuowola.fuelmanager

import android.app.*
import android.content.*
import android.graphics.*
import android.os.Build
import android.provider.MediaStore
import android.view.*
import android.widget.*
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import kotlin.math.*

/** Camera delegates to the system app. Images stay in cache and OCR is bundled/on-device. */
object PhotoOdo {
    private fun file(a:Activity)=File(a.cacheDir,"odo/capture.jpg").apply{parentFile?.mkdirs()}
    fun capture(a:MainActivity){
        val f=file(a);f.delete()
        val uri=FileProvider.getUriForFile(a,"${a.packageName}.files",f)
        val intent=Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT,uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData=ClipData.newRawUri("ODO",uri)
        try{a.startActivityForResult(intent,200)}catch(e:Exception){f.delete();AlertDialog.Builder(a).setMessage("相机不可用，请手动输入 ODO").setPositiveButton("手动输入"){_,_->a.odoInput(false)}.show()}
    }
    fun result(a:MainActivity,code:Int){
        val f=file(a)
        if(code!=Activity.RESULT_OK||!f.exists()){f.delete();return}
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(f.path,bounds)
        val bitmap=BitmapFactory.decodeFile(f.path,BitmapFactory.Options().apply{inSampleSize=max(1,max(bounds.outWidth,bounds.outHeight)/1800)})
        f.delete()
        if(bitmap==null){a.odoInput(false);return}
        val crop=CropView(a,bitmap)
        val box=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL;setPadding(18,12,18,12)
            addView(TextView(a).apply{text="拖动框选仪表 ODO 数字；排除 TRIP、速度和时钟。反光或模糊请重拍。"})
            addView(crop,LinearLayout.LayoutParams(-1,(a.resources.displayMetrics.heightPixels*.52).toInt()))
            addView(Button(a).apply{text="旋转 90°";setOnClickListener{crop.rotate()}})
        }
        val dialog=AlertDialog.Builder(a).setTitle("本机 ODO 识别").setView(box).setNegativeButton("取消",null).setPositiveButton("识别选区",null).create()
        dialog.setOnShowListener{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
            val part=crop.selected()
            if(part==null){Toast.makeText(a,"请先框选 ODO 数字",Toast.LENGTH_SHORT).show();return@setOnClickListener}
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=false
            val recognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer.process(InputImage.fromBitmap(part,0)).addOnSuccessListener{result->
                if(!dialog.isShowing)return@addOnSuccessListener
                val candidates=Regex("(?<![\\d:])\\d{3,7}(?:\\.\\d)?(?![\\d:])").findAll(result.text).map{it.value}.distinct().toList()
                dialog.dismiss()
                if(candidates.size==1)a.odoInput(false,candidates.single())
                else AlertDialog.Builder(a).setTitle("未识别出唯一 ODO").setMessage("请重拍并只框选 ODO，或输入完整仪表里程。OCR 不会自动修改记录。")
                    .setPositiveButton("手动输入"){_,_->a.odoInput(false)}.setNegativeButton("关闭",null).show()
            }.addOnFailureListener{if(dialog.isShowing){dialog.dismiss();a.odoInput(false)}}.addOnCompleteListener{recognizer.close();part.recycle()}
        }}
        dialog.show()
    }
    private class CropView(c:Context, var bitmap:Bitmap):View(c){
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        private var selection:RectF?=null
        private var x0=0f;private var y0=0f
        private fun destination():RectF {
            val scale=min(width.toFloat()/bitmap.width,height.toFloat()/bitmap.height)
            val w=bitmap.width*scale;val h=bitmap.height*scale
            return RectF((width-w)/2,(height-h)/2,(width+w)/2,(height+h)/2)
        }
        override fun onDraw(canvas:Canvas){
            val dest=destination();canvas.drawColor(Color.DKGRAY);canvas.drawBitmap(bitmap,null,dest,paint)
            selection?.let{paint.style=Paint.Style.STROKE;paint.color=Color.rgb(239,159,62);paint.strokeWidth=4f;canvas.drawRect(it,paint);paint.style=Paint.Style.FILL}
        }
        override fun onTouchEvent(e:MotionEvent):Boolean{
            when(e.action){MotionEvent.ACTION_DOWN->{x0=e.x;y0=e.y;selection=RectF(x0,y0,x0,y0)};MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP->{selection=RectF(min(x0,e.x),min(y0,e.y),max(x0,e.x),max(y0,e.y));invalidate()}}
            if(e.action==MotionEvent.ACTION_UP)performClick()
            return true
        }
        override fun performClick():Boolean { super.performClick();return true }
        fun rotate(){val matrix=Matrix().apply{postRotate(90f)};val next=Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,matrix,true);bitmap=next;selection=null;invalidate()}
        fun selected():Bitmap?{
            val r=selection?:return null;val d=destination();val clipped=RectF(r)
            if(!clipped.intersect(d)||clipped.width()<12||clipped.height()<12)return null
            val scale=bitmap.width/d.width()
            val x=((clipped.left-d.left)*scale).toInt().coerceIn(0,bitmap.width-1);val y=((clipped.top-d.top)*scale).toInt().coerceIn(0,bitmap.height-1)
            val w=(clipped.width()*scale).toInt().coerceIn(1,bitmap.width-x);val h=(clipped.height()*scale).toInt().coerceIn(1,bitmap.height-y)
            return Bitmap.createBitmap(bitmap,x,y,w,h)
        }
    }
}
