package io.github.bieshuowola.fuelmanager

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

/** All access is on the application's main thread. AtomicFile couples fuel mirror + distance revision. */
class LocalRepository internal constructor(context:Context) {
    private val baseDir=context.filesDir
    private val file=AtomicFile(File(baseDir,"tracking-v1.json"))
    val state:JSONObject
    init {
        state=try { JSONObject(file.openRead().bufferedReader().use{it.readText()}).also{require(it.optInt("schema")==1)} }
        catch(e:Exception) {
            if(file.baseFile.exists())file.baseFile.copyTo(File(baseDir,"tracking-corrupt-${System.currentTimeMillis()}.json"))
            JSONObject().put("schema",1).put("unknown",file.baseFile.exists()).put("trips",JSONArray()).put("calibrations",JSONArray())
        }
    }
    fun commit() {
        val output=file.startWrite()
        try { output.write(state.toString().toByteArray());file.finishWrite(output) }
        catch(e:Exception){file.failWrite(output);throw e}
    }
    fun archive() {
        val dir=File(baseDir,"restore-points").apply{mkdirs()}
        File(dir,"before-${System.currentTimeMillis()}.json").writeText(state.toString())
        // Retain the last 10 safety copies rather than silently overwriting a single backup.
        dir.listFiles()?.sortedByDescending{it.lastModified()}?.drop(10)?.forEach{it.delete()}
    }
    fun recover(reason:String) {
        if(state.optBoolean("active")){state.put("active",false);state.put("unknown",true);state.put("status",TripState.NEEDS_ODO.name);state.put("reason",reason);commit()}
    }
    fun start(confirmed:Boolean) {
        state.put("active",true).put("acquiring",true).put("confirmed",confirmed).put("status",if(confirmed)"RECORDING" else "SUSPECTED")
            .put("sessionStart",Instant.now().toString()).put("sessionKm",0.0)
        commit()
    }
    fun distance(result:DistanceResult, elapsedMs:Long) {
        state.put("seen",state.optLong("seen")+1)
        if(!result.accepted)state.put("rejected",state.optLong("rejected")+1)
        if(result.gap){state.put("unknown",true);state.put("gaps",state.optLong("gaps")+1);state.put("reason",result.reason)}
        if(result.metres>0) {
            val field=if(state.optBoolean("confirmed"))"km" else "pendingKm"
            state.put(field,state.optDouble(field,0.0)+result.metres/1000)
            state.put("sessionKm",state.optDouble("sessionKm",0.0)+result.metres/1000)
        }
        if(result.accepted)state.put("lastFixElapsed",elapsedMs).put("acquiring",false)
        state.put("at",Instant.now().toString());commit()
    }
    fun stop(interrupted:Boolean=false) {
        if(!state.optBoolean("active"))return
        val trips=state.optJSONArray("trips")?:JSONArray()
        trips.put(JSONObject().put("start",state.optString("sessionStart")).put("end",Instant.now().toString())
            .put("km",state.optDouble("sessionKm",0.0)).put("confirmed",state.optBoolean("confirmed")).put("interrupted",interrupted))
        while(trips.length()>500)trips.remove(0)
        state.put("trips",trips).put("active",false)
        if(interrupted)state.put("unknown",true).put("reason","recording_interrupted")
        state.put("status",if(state.optDouble("pendingKm",0.0)>0)"AWAITING_CONFIRMATION" else if(state.optBoolean("unknown"))"NEEDS_ODO" else "ENDED")
        commit()
    }
    fun confirmPending(isMotorcycle:Boolean) {
        if(isMotorcycle)state.put("km",state.optDouble("km",0.0)+state.optDouble("pendingKm",0.0))
        state.put("pendingKm",0.0)
        // Confirmation of transport does not clear missing GPS or interruption flags.
        if(state.optBoolean("active"))state.put("confirmed",isMotorcycle)
        commit()
    }
    fun syncFuel(fuel:JSONObject) {
        val events=fuel.getJSONArray("events")
        val old=state.optJSONObject("fuel")?.optJSONArray("events")
        val changed=old!=null && old.toString()!=events.toString()
        if(old==null || changed) {
            val prefix=old!=null && events.length()>old.length() && (0 until old.length()).all{events.getJSONObject(it).toString()==old.getJSONObject(it).toString()}
            val first=old?.optJSONObject(old.length()-1)?.optDouble("odometer")
            val last=events.optJSONObject(events.length()-1)?.optDouble("odometer")
            val initialized=old!=null && old.length()==0 && events.length()==1 && events.getJSONObject(0).optString("kind")=="init"
            if(initialized){
                state.put("acquiring",false).put("unknown",false).put("km",0.0).put("pendingKm",0.0).put("reason","")
            } else if(prefix && first!=null && last!=null && last>=first){
                val predicted=state.optDouble("km",0.0)+state.optDouble("pendingKm",0.0)
                val cal=OdometerCalibration.reconcile(first,last,predicted)
                val cals=state.optJSONArray("calibrations")?:JSONArray()
                cals.put(JSONObject().put("at",Instant.now().toString()).put("previous",first).put("actual",last).put("gpsKm",predicted).put("deltaKm",cal.errorKm))
                while(cals.length()>100)cals.remove(0)
                state.put("calibrations",cals).put("tripsAtCalibration",state.optJSONArray("trips")?.length()?:0)
                state.put("acquiring",false).put("unknown",false).put("km",0.0).put("pendingKm",0.0).put("reason","")
            } else {
                // Import, undo or reset has no provable relationship to saved GPS increments.
                if(old!=null)archive()
                state.put("km",0.0).put("pendingKm",0.0).put("unknown",events.length()>0 && old!=null)
            }
            LocationTrackingService.live?.reanchor()
        }
        state.put("fuel",fuel);commit()
    }
    fun projection():JSONObject {
        val raw=state.optDouble("km",0.0)
        val count=state.optJSONArray("calibrations")?.length()?:0
        val needsCalibration=raw>=(if(count<5)100.0 else 150.0) || (count<5 && (state.optJSONArray("trips")?.length()?:0)-state.optInt("tripsAtCalibration")>=1 && raw>0)
        return JSONObject().put("events",state.optJSONObject("fuel")?.optJSONArray("events")?.toString()?:"[]")
            // One explicit GPS coverage margin; do not add another fuel multiplier.
            .put("km",if(raw>0)raw+maxOf(0.05,raw*0.05) else 0.0)
            .put("unknown",state.optBoolean("unknown") || state.optBoolean("acquiring") || state.optDouble("pendingKm",0.0)>0 || needsCalibration)
            .put("at",state.optString("at",Instant.now().toString()))
    }
    fun backup():JSONObject=JSONObject().put("androidBackup",1).put("createdAt",Instant.now().toString()).put("tracking",JSONObject(state.toString()))
    companion object {
        private var instance:LocalRepository?=null
        fun get(c:Context):LocalRepository=instance?:LocalRepository(c.applicationContext).also{instance=it}
    }
}
