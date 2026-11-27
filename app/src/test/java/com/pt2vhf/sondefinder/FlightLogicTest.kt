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
        // Brasília (Esplanada) → Goiânia ≈ 173 km
        val d = FlightLogic.distanceM(-15.7998, -47.8645, -16.6869, -49.2648)
        assertEquals(173_000.0, d, 3_000.0)
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
