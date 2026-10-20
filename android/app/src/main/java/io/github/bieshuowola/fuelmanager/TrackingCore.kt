package io.github.bieshuowola.fuelmanager

import kotlin.math.*

enum class TripState { STANDBY, SUSPECTED, RECORDING, SHORT_STOP, ENDED, LOCATION_ERROR, AWAITING_CONFIRMATION, NEEDS_ODO }
data class Fix(val lat: Double, val lon: Double, val accuracy: Double, val elapsedMs: Long, val speed: Double? = null)
data class DistanceResult(val metres: Double = 0.0, val accepted: Boolean = false, val gap: Boolean = false, val reason: String = "")

/** Retains only a live anchor, never extrapolates or joins across missing intervals. */
class DistanceAccumulator(val maxAccuracy: Double = 25.0) {
    private var anchor: Fix? = null
    private var lastSeen = -1L
    private var broken = false
    fun reanchor(afterElapsedMs:Long = -1L) { anchor = null; lastSeen = afterElapsedMs; broken = false }
    fun offer(f: Fix, nowMs: Long): DistanceResult {
        if (!f.lat.isFinite() || !f.lon.isFinite() || abs(f.lat)>90 || abs(f.lon)>180 || !f.accuracy.isFinite() || f.accuracy<0 || f.elapsedMs>nowMs+1000 || nowMs-f.elapsedMs>15000)
            return DistanceResult(reason="stale_or_invalid")
        if (f.elapsedMs<=lastSeen) return DistanceResult(reason="duplicate_or_out_of_order")
        val gap = broken || (lastSeen>=0 && f.elapsedMs-lastSeen>30000)
        if (f.accuracy>maxAccuracy) { broken=true; anchor=null; return DistanceResult(gap=true, reason="accuracy") }
        val previousSeen=lastSeen
        lastSeen=f.elapsedMs; broken=false
        val prev=anchor
        if (prev==null || gap) { anchor=f; return DistanceResult(accepted=true,gap=gap,reason="anchor") }
        val dt=(f.elapsedMs-prev.elapsedMs)/1000.0
        val metres=haversine(prev.lat,prev.lon,f.lat,f.lon)
        if (dt<=0 || metres/dt>65 || (f.speed!=null && (!f.speed.isFinite() || f.speed<0 || f.speed>65))) {
            anchor=f; return DistanceResult(gap=true,reason="jump")
        }
        // A retained drift anchor spans a stop; endpoint speeds cannot describe that whole interval.
        if(dt<=15 && prev.elapsedMs==previousSeen && prev.speed!=null && f.speed!=null) {
            val expected=(prev.speed+f.speed)/2*dt
            if(abs(metres-expected)>max(25.0,(prev.accuracy+f.accuracy)*2+expected*0.65)) {
                anchor=f;return DistanceResult(gap=true,reason="speed_position_disagreement")
            }
        }
        val deadband=max(2.5,min(10.0,(prev.accuracy+f.accuracy)*0.3))
        // Reported stationary fixes cannot walk the anchor through GNSS jitter.
        if ((f.speed!=null && f.speed<0.65 && metres<max(15.0,deadband*2)) || metres<deadband)
            return DistanceResult(accepted=true,reason="stationary")
        anchor=f
        return DistanceResult(metres,true,reason="segment")
    }
    companion object {
        fun haversine(a:Double,b:Double,c:Double,d:Double):Double {
            val x=Math.toRadians(c-a); val y=Math.toRadians(d-b)
            val h=sin(x/2).pow(2)+cos(Math.toRadians(a))*cos(Math.toRadians(c))*sin(y/2).pow(2)
            return 6371000*2*asin(sqrt(h.coerceIn(0.0,1.0)))
        }
    }
}

class TripDetector {
    var state=TripState.STANDBY; private set
    private var lastMotion=0L
    private var firstMotion=0L
    private var observations=0
    fun start(confirmed:Boolean, now:Long) { state=if(confirmed) TripState.RECORDING else TripState.SUSPECTED;lastMotion=now;firstMotion=now;observations=0 }
    fun observe(now:Long, metres:Double, speed:Double?) : TripState {
        if(metres>2.5 && (speed==null || speed>0.65)) {
            if(observations==0)firstMotion=now
            observations++;lastMotion=now
            if(state==TripState.SUSPECTED && observations>=3 && now-firstMotion>=15000)state=TripState.AWAITING_CONFIRMATION
            else if(state==TripState.SHORT_STOP)state=TripState.RECORDING
        } else if(state==TripState.RECORDING && now-lastMotion>=30000)state=TripState.SHORT_STOP
        if(now-lastMotion>=600000)state=TripState.ENDED
        return state
    }
    fun error(){state=TripState.LOCATION_ERROR}
}

object OdometerCalibration {
    data class Interval(val actualKm:Double,val gpsKm:Double,val errorKm:Double,val relativeError:Double?)
    fun reconcile(previous:Double, actual:Double, predicted:Double):Interval {
        require(previous.isFinite()&&actual.isFinite()&&predicted.isFinite()&&actual>=previous&&actual<=2000000&&predicted>=0)
        val distance=actual-previous
        return Interval(distance,predicted,distance-predicted,if(distance>0)(predicted-distance)/distance else null)
    }
    /** Three digits identify a unique forward candidate only within a conservative 200 km bound. */
    fun complete(last:Double, digits:String, maxForward:Double=200.0):Double? {
        if(!last.isFinite()||last<0||!digits.matches(Regex("\\d{3}")))return null
        val start=floor(last/1000)*1000+digits.toInt()
        val candidates=listOf(start,start+1000).filter{it>=last&&it-last<=maxForward&&it<=2000000}
        return candidates.singleOrNull()
    }
}
