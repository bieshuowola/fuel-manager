package io.github.bieshuowola.fuelmanager

import org.junit.Assert.*
import org.junit.Test

/** Fixed, hand-authored road scenarios; not recordings or an accuracy claim. */
class TrackingCoreTest {
    private fun fix(metres:Double,t:Long,speed:Double?=8.0,accuracy:Double=4.0)=Fix(26.66+metres/111194.9266,119.55,accuracy,t,speed)
    @Test fun continuousOutdoorRide(){
        val a=DistanceAccumulator();var metres=0.0
        for(i in 0..40)metres+=a.offer(fix(i*24.0,i*3000L),i*3000L).metres
        assertEquals(960.0,metres,0.1)
    }
    @Test fun trafficLightJitterDoesNotConsumeFuel(){
        val a=DistanceAccumulator();a.offer(fix(0.0,0),0)
        for(i in 1..20)assertEquals(0.0,a.offer(fix(if(i%2==0)2.0 else -2.0,i*3000L,0.0),i*3000L).metres,0.0)
        assertEquals(24.0,a.offer(fix(24.0,63000),63000).metres,0.05)
    }
    @Test fun congestedSlowMotionIsAccumulatedInBatches(){
        val a=DistanceAccumulator();var metres=0.0
        for(i in 0..30)metres+=a.offer(fix(i*2.0,i*3000L,0.8),i*3000L).metres
        assertTrue(metres>=57&&metres<=60.1)
    }
    @Test fun tunnelNeverDrawsStraightLineAcrossGap(){
        val a=DistanceAccumulator();a.offer(fix(0.0,0),0)
        assertEquals(24.0,a.offer(fix(24.0,3000),3000).metres,0.1)
        val out=a.offer(fix(1024.0,93000),93000)
        assertTrue(out.gap);assertEquals(0.0,out.metres,0.0)
        assertEquals(24.0,a.offer(fix(1048.0,96000),96000).metres,0.1)
    }
    @Test fun undergroundPoorAccuracyRequiresCalibration(){
        val a=DistanceAccumulator();a.offer(fix(0.0,0),0)
        assertTrue(a.offer(fix(200.0,3000,10.0,120.0),3000).gap)
        val recover=a.offer(fix(300.0,6000),6000);assertTrue(recover.gap);assertEquals(0.0,recover.metres,0.0)
    }
    @Test fun suddenJumpIsRejectedAndReanchored(){
        val a=DistanceAccumulator();a.offer(fix(0.0,0),0)
        assertTrue(a.offer(fix(2000.0,3000),3000).gap)
        assertEquals(24.0,a.offer(fix(2024.0,6000),6000).metres,0.1)
    }
    @Test fun plausibleSpeedJumpStillFailsPositionConsistency(){
        val a=DistanceAccumulator();a.offer(fix(0.0,0),0)
        val jump=a.offer(fix(100.0,3000,8.0),3000)
        assertTrue(jump.gap);assertEquals("speed_position_disagreement",jump.reason);assertEquals(0.0,jump.metres,0.0)
    }
    @Test fun duplicateAndOutOfOrderNeverDoubleCount(){
        val a=DistanceAccumulator();a.offer(fix(0.0,0),0);a.offer(fix(24.0,3000),3000)
        assertEquals(0.0,a.offer(fix(24.0,3000),3000).metres,0.0)
        assertEquals(0.0,a.offer(fix(12.0,1500),3000).metres,0.0)
        assertEquals(24.0,a.offer(fix(48.0,6000),6000).metres,0.1)
    }
    @Test fun staleFutureNaNAndInvalidCoordinatesAreRejected(){
        for(f in listOf(fix(0.0,0),fix(0.0,30000),Fix(Double.NaN,0.0,2.0,20000),Fix(91.0,0.0,2.0,20000),Fix(0.0,0.0,Double.NaN,20000)))
            assertFalse(DistanceAccumulator().offer(f,20000).accepted)
    }
    @Test fun calibrationResetsAnchorWithoutJoiningOverlap(){
        val a=DistanceAccumulator();a.offer(fix(0.0,0),0);a.offer(fix(24.0,3000),3000);a.reanchor()
        assertEquals(0.0,a.offer(fix(48.0,6000),6000).metres,0.0)
        assertEquals(24.0,a.offer(fix(72.0,9000),9000).metres,0.1)
    }
    @Test fun cachedFixFromBeforeCalibrationCannotBeReused(){
        val a=DistanceAccumulator();a.offer(fix(0.0,0),0);a.reanchor(4000)
        assertFalse(a.offer(fix(24.0,3000),5000).accepted)
        assertEquals(0.0,a.offer(fix(48.0,6000),6000).metres,0.0)
    }
    @Test fun unavailableSpeedCanStillUseGoodPositions(){
        val a=DistanceAccumulator();a.offer(fix(0.0,0,null),0)
        assertEquals(24.0,a.offer(fix(24.0,3000,null),3000).metres,0.1)
    }
    @Test fun noFixIntervalRemainsUnknownAfterReacquisition(){
        val a=DistanceAccumulator();a.offer(fix(0.0,0),0)
        val r=a.offer(fix(5000.0,3600000),3600000)
        assertTrue(r.gap);assertEquals(0.0,r.metres,0.0)
    }
    @Test fun temporaryStopDoesNotEndTrip(){
        val d=TripDetector();d.start(true,0)
        assertEquals(TripState.SHORT_STOP,d.observe(60000,0.0,0.0))
        assertEquals(TripState.RECORDING,d.observe(63000,12.0,4.0))
        assertEquals(TripState.ENDED,d.observe(663000,0.0,0.0))
    }
    @Test fun slowCredibleProgressCannotBeMistakenForTenMinuteParking(){
        val d=TripDetector();d.start(true,0)
        for(i in 1..30)assertEquals(TripState.RECORDING,d.observe(i*32000L,16.0,0.5))
    }
    @Test fun vehicleClassificationNeverConfirmsMotorcycle(){
        val d=TripDetector();d.start(false,0)
        d.observe(3000,24.0,8.0);d.observe(12000,72.0,8.0)
        assertEquals(TripState.SUSPECTED,d.state)
        assertEquals(TripState.AWAITING_CONFIRMATION,d.observe(21000,72.0,8.0))
    }
    @Test fun gpsUnderreadsAndOverreadsAreReconciledOnlyInInterval(){
        val under=OdometerCalibration.reconcile(26000.0,26132.0,128.0)
        assertEquals(4.0,under.errorKm,0.0);assertEquals(132.0,under.actualKm,0.0)
        val over=OdometerCalibration.reconcile(26000.0,26124.0,128.0)
        assertEquals(-4.0,over.errorKm,0.0)
        assertEquals(0.0,OdometerCalibration.reconcile(26132.0,26140.0,8.0).errorKm,0.0)
    }
    @Test fun lastThreeDigitsCarryAndRejectAmbiguity(){
        assertEquals(27012.0,OdometerCalibration.complete(26980.0,"012")!!,0.0)
        assertEquals(26132.0,OdometerCalibration.complete(26000.0,"132")!!,0.0)
        assertNull(OdometerCalibration.complete(26000.0,"900"))
        assertNull(OdometerCalibration.complete(26000.0,"12"))
        assertNull(OdometerCalibration.complete(26000.0,"132",2000.0))
    }
    @Test(expected=IllegalArgumentException::class) fun odometerCannotGoBackwards(){OdometerCalibration.reconcile(26000.0,25999.0,5.0)}
}
