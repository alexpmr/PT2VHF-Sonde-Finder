package com.pt2vhf.sondefinder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlightLogicTest {
    private val now = 1_800_000_000_000L

    @Test fun phaseFollowsVerticalSpeedWhileLive() {
        assertEquals(Phase.UP, FlightLogic.phase(5.2, 30_000))
        assertEquals(Phase.DOWN, FlightLogic.phase(-8.0, 30_000))
        assertEquals(Phase.NONE, FlightLogic.phase(0.3, 30_000))     // floating / landed
        assertEquals(Phase.NONE, FlightLogic.phase(Double.NaN, 0))
    }

    @Test fun phaseIsNoneWhenNotHeardFor10Minutes() {
        assertEquals(Phase.NONE, FlightLogic.phase(-8.0, FlightLogic.LIVE_MS + 1))
        assertEquals(Phase.DOWN, FlightLogic.phase(-8.0, FlightLogic.LIVE_MS))
    }

    @Test fun distanceMatchesKnownValue() {
        // 1° of latitude on a 6371 km sphere = 111.195 km; 1° of longitude at 60° is half of that
        assertEquals(111_195.0, FlightLogic.distanceM(-15.0, -47.0, -16.0, -47.0), 5.0)
        assertEquals(55_597.0, FlightLogic.distanceM(60.0, 10.0, 60.0, 11.0), 150.0)
        assertEquals(0.0, FlightLogic.distanceM(-15.0, -47.0, -15.0, -47.0), 1e-6)
    }

    private fun pred(lat: Double, lon: Double, t: Long) = Prediction(listOf(Point(lat + 1, lon, 20000.0, t - 3_600_000), Point(lat, lon, 1000.0, t)))

    @Test fun alarmHitsOnlyLandingsInsideRadiusNearestFirst() {
        val me = Pair(-15.79, -47.88)
        val preds = mapOf(
            "NEAR" to pred(-15.80, -47.88, now + 600_000),   // ~1.1 km
            "MID" to pred(-15.83, -47.88, now + 600_000),    // ~4.4 km
            "FAR" to pred(-16.20, -47.88, now + 600_000),    // ~45 km
        )
        val hits = FlightLogic.alarmHits(me.first, me.second, 5_000.0, preds, now, emptySet())
        assertEquals(listOf("NEAR", "MID"), hits.map { it.serial })
        assertTrue(hits[0].distM < 1_500)
    }

    @Test fun alarmSkipsAlreadyAlertedAndPastLandings() {
        val preds = mapOf(
            "DONE" to pred(-15.80, -47.88, now + 600_000),
            "OLD" to pred(-15.80, -47.88, now - 10 * 60_000L),
            "UNTIMED" to pred(-15.80, -47.88, 0),
        )
        val hits = FlightLogic.alarmHits(-15.79, -47.88, 5_000.0, preds, now, setOf("DONE"))
        assertEquals(listOf("UNTIMED"), hits.map { it.serial })
    }
}

class LandingTest {
    private val now = 1_800_000_000_000L
    private val min = 60_000L

    @Test fun fallingUsesPrediction() {
        val l = FlightLogic.landing(5000.0, -10.0, now - 5_000, now + 12 * min, 0.0, now, false)!!
        org.junit.Assert.assertFalse(l.landed); org.junit.Assert.assertEquals(now + 12 * min, l.timeMs)
    }
    @Test fun fallingWithoutPredictionUsesDescentRate() {
        // 6000 m at 10 m/s = 600 s after the last packet
        val l = FlightLogic.landing(6000.0, -10.0, now, null, 0.0, now, false)!!
        org.junit.Assert.assertFalse(l.landed); org.junit.Assert.assertEquals(now + 600_000, l.timeMs)
    }
    @Test fun ascendingShowsNothing() =
        org.junit.Assert.assertNull(FlightLogic.landing(20000.0, 5.0, now - 1000, now + 90 * min, 0.0, now, false))
    @Test fun downUsesPredictedTime() {
        val last = now - 60 * min
        val l = FlightLogic.landing(1500.0, -8.0, last, last + 3 * min, 0.0, now, false)!!
        org.junit.Assert.assertTrue(l.landed); org.junit.Assert.assertEquals(last + 3 * min, l.timeMs)
    }
    @Test fun downWithoutPredictionAddsFallTime() {
        val last = now - 60 * min
        val l = FlightLogic.landing(800.0, -8.0, last, null, 0.0, now, false)!!
        org.junit.Assert.assertTrue(l.landed); org.junit.Assert.assertEquals(last + 100_000, l.timeMs)
    }
    @Test fun downOnTheGroundIsLastPacket() {
        val last = now - 3 * 3600_000L
        val l = FlightLogic.landing(900.0, 0.0, last, null, 0.0, now, false)!!
        org.junit.Assert.assertTrue(l.landed); org.junit.Assert.assertEquals(last, l.timeMs)
    }
    @Test fun recoveredIsDownEvenIfRecent() {
        val l = FlightLogic.landing(900.0, 0.0, now - min, null, 0.0, now, true)!!
        org.junit.Assert.assertTrue(l.landed)
    }
    @Test fun oldPredictionBeforeLastPacketIsIgnored() {
        val last = now - 60 * min
        val l = FlightLogic.landing(0.0, 0.0, last, last - 30 * min, 0.0, now, false)!!
        org.junit.Assert.assertEquals(last, l.timeMs)
    }
}
