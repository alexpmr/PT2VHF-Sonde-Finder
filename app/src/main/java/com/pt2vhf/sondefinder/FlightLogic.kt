package com.pt2vhf.sondefinder

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Flight phase shown on the map: balloon going up, parachute coming down, plain dot otherwise. */
enum class Phase { UP, DOWN, NONE }

/** Pure flight logic (no Android types), covered by unit tests. */
object FlightLogic {
    /** A sonde not heard for this long is no longer drawn as flying. */
    const val LIVE_MS = 10 * 60_000L

    fun phase(velV: Double, ageMs: Long): Phase = when {
        ageMs > LIVE_MS || velV.isNaN() -> Phase.NONE
        velV > 1.0 -> Phase.UP
        velV < -1.0 -> Phase.DOWN
        else -> Phase.NONE
    }

    /** Great-circle distance in metres. */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    data class AlarmHit(val serial: String, val landing: Point, val distM: Double)

    /**
     * Landing alarm: sondes whose predicted landing is within [maxM] of the user and still ahead
     * (landing time not more than 5 min in the past), skipping those already [alerted]. Nearest first.
     */
    fun alarmHits(userLat: Double, userLon: Double, maxM: Double, preds: Map<String, Prediction>,
                  nowMs: Long, alerted: Set<String>): List<AlarmHit> =
        preds.mapNotNull { (serial, p) ->
            val land = p.landing ?: return@mapNotNull null
            if (serial in alerted) return@mapNotNull null
            if (land.timeMs != 0L && land.timeMs < nowMs - 5 * 60_000L) return@mapNotNull null
            val d = distanceM(userLat, userLon, land.lat, land.lon)
            if (d <= maxM) AlarmHit(serial, land, d) else null
        }.sortedBy { it.distM }

    /** Landing field: [landed] false → expected landing at [timeMs] (future); true → estimated time it landed. */
    data class Landing(val landed: Boolean, val timeMs: Long)

    /**
     * Landing time for the "Landing" field.
     * - Still descending (heard in the last 10 min, falling): when it will land — the prediction's time,
     *   else altitude above ground / descent rate from the last packet.
     * - Down (recovered, or not heard for 10 min): estimated time it landed — the prediction's landing
     *   time if it is not before the last packet, else the last packet plus the fall time left, else the
     *   last packet itself. Never in the future.
     * - Ascending, or no data: null.
     */
    fun landing(alt: Double, velV: Double, lastMs: Long, predLandMs: Long?, groundAlt: Double,
                nowMs: Long, recovered: Boolean): Landing? {
        if (lastMs <= 0L) return null
        val falling = !velV.isNaN() && velV < -0.5
        val fallLeftMs = if (falling && !alt.isNaN()) (((alt - groundAlt).coerceAtLeast(0.0) / -velV) * 1000).toLong() else 0L
        val pred = predLandMs?.takeIf { it > 0L }
        if (recovered || nowMs - lastMs > LIVE_MS) {
            val t = when {
                pred != null && pred >= lastMs - 60_000L -> pred
                falling -> lastMs + fallLeftMs
                else -> lastMs
            }
            return Landing(true, minOf(t, nowMs))
        }
        if (!falling) return null
        val t = if (pred != null && pred > nowMs) pred else lastMs + fallLeftMs
        return Landing(false, maxOf(t, nowMs))
    }
}
