package io.github.bieshuowola.fuelmanager

import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.json.*
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
class LocalRepositoryTest {
    private lateinit var repo:LocalRepository
    private fun event(odo:Double,kind:String="mileage")=JSONObject().put("kind",kind).put("odometer",odo).put("at","2026-10-20T08:00:00Z")
    private fun fuel(vararg e:JSONObject)=JSONObject().put("version",1).put("events",JSONArray(e.toList())).put("settings",JSONObject().put("capacity",12.5))
    private fun init() {repo.syncFuel(fuel());repo.syncFuel(fuel(event(26000.0,"init")))}
    @Before fun fresh(){
        val c=RuntimeEnvironment.getApplication();File(c.filesDir,"tracking-v1.json").delete();File(c.filesDir,"tracking-v1.json.bak").delete();repo=LocalRepository(c)
    }
    @Test fun explicitFirstFullTankIsTrustedWithoutFakeGps(){init();assertFalse(repo.projection().getBoolean("unknown"));assertEquals(0.0,repo.projection().getDouble("km"),0.0)}
    @Test fun actualOdoConsumesOverlapExactlyOnce(){
        init();repo.state.put("km",128.0);repo.commit();repo.syncFuel(fuel(event(26000.0,"init"),event(26132.0)))
        assertEquals(0.0,repo.state.getDouble("km"),0.0)
        assertEquals(4.0,repo.state.getJSONArray("calibrations").getJSONObject(0).getDouble("deltaKm"),0.0)
        repo.state.put("km",8.0);repo.syncFuel(fuel(event(26000.0,"init"),event(26132.0)))
        assertEquals(8.0,repo.state.getDouble("km"),0.0)
    }
    @Test fun pendingCarTripNeverAppearsAsReliableMileage(){
        init();repo.start(false);repo.distance(DistanceResult(1500.0,true),10000);repo.stop()
        assertTrue(repo.projection().getBoolean("unknown"));assertEquals(0.0,repo.projection().getDouble("km"),0.0)
        repo.confirmPending(false);assertFalse(repo.projection().getBoolean("unknown"))
    }
    @Test fun confirmingBikeTransfersOnceAndRetainsGap(){
        init();repo.start(false);repo.distance(DistanceResult(1500.0,true,true,"tunnel"),10000);repo.stop();repo.confirmPending(true);repo.confirmPending(true)
        assertEquals(1.5,repo.state.getDouble("km"),0.0);assertTrue(repo.projection().getBoolean("unknown"))
    }
    @Test fun killedProcessAndBootNeverResumeAsFullyRecorded(){
        init();repo.start(true);repo.distance(DistanceResult(1000.0,true),10000)
        val restored=LocalRepository(RuntimeEnvironment.getApplication());restored.recover("process_death")
        assertFalse(restored.state.getBoolean("active"));assertTrue(restored.projection().getBoolean("unknown"));assertEquals(1.0,restored.state.getDouble("km"),0.0)
    }
    @Test fun fuelAndDistancePersistInSameAtomicRevision(){
        init();repo.state.put("km",2.0);repo.commit()
        val restored=LocalRepository(RuntimeEnvironment.getApplication())
        assertEquals(repo.state.toString(),restored.state.toString());assertEquals(repo.backup().getJSONObject("tracking").toString(),restored.backup().getJSONObject("tracking").toString())
    }
    @Test fun settingsChangeDoesNotDiscardAccumulatedDistance(){
        init();repo.state.put("km",2.0);val f=fuel(event(26000.0,"init"));f.getJSONObject("settings").put("capacity",13.0);repo.syncFuel(f)
        assertEquals(2.0,repo.state.getDouble("km"),0.0)
    }
    @Test fun undoCannotReuseOldGpsAgainstAnotherAnchor(){
        init();repo.syncFuel(fuel(event(26000.0,"init"),event(26100.0)));repo.state.put("km",20.0)
        repo.syncFuel(fuel(event(26000.0,"init")))
        assertTrue(repo.projection().getBoolean("unknown"));assertEquals(0.0,repo.state.getDouble("km"),0.0)
    }
    @Test fun badGpsAccuracyOrGapNeverIncreasesReliableFuel(){
        init();repo.start(true);repo.distance(DistanceResult(1000.0,true),3000);repo.distance(DistanceResult(gap=true,reason="poor_accuracy"),6000)
        assertEquals(1.0,repo.state.getDouble("km"),0.0);assertTrue(repo.projection().getBoolean("unknown"))
    }
    @Test fun noFixIsNotTreatedAsZeroTravel(){init();repo.start(true);assertTrue(repo.projection().getBoolean("unknown"))}
    @Test fun intervalThresholdRequiresRealCalibration(){init();repo.state.put("km",100.0);assertTrue(repo.projection().getBoolean("unknown"))}
}
