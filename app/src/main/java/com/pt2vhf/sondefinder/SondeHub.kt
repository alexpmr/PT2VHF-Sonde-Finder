package com.pt2vhf.sondefinder

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant

data class Sonde(
    val serial: String,
    val type: String,
    val lat: Double,
    val lon: Double,
    val alt: Double,
    val velV: Double,
    val velH: Double,
    val heading: Double,
    val freq: Double,
    val timeMs: Long,
    val uploader: String,
    val temp: Double = Double.NaN,      // °C (air temperature sensor)
    val pressure: Double = Double.NaN,  // hPa (only sondes with a pressure sensor, or computed)
)

data class Recovery(val serial: String, val recovered: Boolean, val by: String, val description: String, val timeMs: Long)

data class Point(val lat: Double, val lon: Double, val alt: Double, val timeMs: Long)

data class Prediction(val path: List<Point>) {
    val landing: Point? get() = path.lastOrNull()
}

/** Minimal client for the public SondeHub v2 REST API (https://api.v2.sondehub.org). */
object SondeHub {
    private const val BASE = "https://api.v2.sondehub.org"
    private const val UA = "PT2VHF-SondeFinder/1.0 (Android)"

    // readMs: live requests must fail fast (a stuck request holds back the next update);
    // history summaries are slow on the server and keep the long timeout.
    private fun get(path: String, readMs: Int = 60000): String {
        val c = URL(BASE + path).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = readMs
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", "application/json")
        try {
            val code = c.responseCode
            if (code !in 200..299) throw RuntimeException("HTTP $code")
            // Some endpoints always answer gzip; decompress if the stack did not already.
            val raw = java.io.BufferedInputStream(c.inputStream)
            raw.mark(2)
            val b0 = raw.read(); val b1 = raw.read()
            raw.reset()
            val stream = if (b0 == 0x1f && b1 == 0x8b) java.util.zip.GZIPInputStream(raw) else raw
            return stream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    fun parseTime(s: String?): Long {
        if (s.isNullOrBlank()) return 0
        return try {
            Instant.parse(if (s.endsWith("Z") || s.contains("+")) s else s + "Z").toEpochMilli()
        } catch (e: Exception) {
            0
        }
    }

    private fun JSONObject.d(k: String): Double =
        if (has(k) && !isNull(k)) optDouble(k, Double.NaN) else Double.NaN

    private fun parseSonde(serial: String, o: JSONObject): Sonde? {
        val lat = o.d("lat")
        val lon = o.d("lon")
        if (lat.isNaN() || lon.isNaN()) return null
        val sub = o.optString("subtype", "")
        val type = if (sub.isNotBlank() && sub != "null") sub else o.optString("type", "")
        return Sonde(
            serial = o.optString("serial", serial),
            type = type,
            lat = lat,
            lon = lon,
            alt = o.d("alt"),
            velV = o.d("vel_v"),
            velH = o.d("vel_h"),
            heading = o.d("heading"),
            freq = o.d("frequency"),
            timeMs = parseTime(o.optString("datetime", null)),
            uploader = o.optString("uploader_callsign", ""),
            temp = o.d("temp"),
            pressure = o.d("pressure"),
        )
    }

    /** Latest telemetry of every sonde heard within [radiusM] of the point in the last [lastSec]. */
    fun nearby(lat: Double, lon: Double, radiusM: Int, lastSec: Int): List<Sonde> {
        val body = get("/sondes?lat=$lat&lon=$lon&distance=$radiusM&last=$lastSec", 15000)
        val root = JSONObject(body)
        val out = ArrayList<Sonde>()
        for (k in root.keys()) {
            val o = root.optJSONObject(k) ?: continue
            parseSonde(k, o)?.let { out.add(it) }
        }
        return out
    }

    /** Flight track (positions) of one sonde over the last [duration] (e.g. "3h"). */
    fun track(serial: String, duration: String = "6h"): List<Point> {
        val s = URLEncoder.encode(serial, "UTF-8")
        val root = JSONObject(get("/sondes/telemetry?duration=$duration&serial=$s", 30000))
        val frames = root.optJSONObject(serial) ?: return emptyList()
        val out = ArrayList<Point>()
        for (k in frames.keys()) {
            val o = frames.optJSONObject(k) ?: continue
            val la = o.d("lat")
            val lo = o.d("lon")
            if (la.isNaN() || lo.isNaN()) continue
            out.add(Point(la, lo, o.d("alt"), parseTime(o.optString("datetime", k))))
        }
        out.sortBy { it.timeMs }
        return out
    }

    /** Landing prediction calculated by SondeHub for this sonde, if any. */
    fun prediction(serial: String): Prediction? = predictions(listOf(serial))[serial]

    /** Landing predictions for several sondes in one request (serial → prediction). */
    fun predictions(serials: Collection<String>): Map<String, Prediction> {
        if (serials.isEmpty()) return emptyMap()
        val s = URLEncoder.encode(serials.joinToString(","), "UTF-8")
        val arr = JSONArray(get("/predictions?vehicles=$s", 20000))
        val out = HashMap<String, Prediction>()
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            val serial = p.optString("vehicle")
            if (serial !in serials) continue
            val raw = p.opt("data")
            val data = when (raw) {
                is JSONArray -> raw
                is String -> try { JSONArray(raw) } catch (e: Exception) { null }
                else -> null
            } ?: continue
            val pts = ArrayList<Point>()
            for (j in 0 until data.length()) {
                val q = data.optJSONObject(j) ?: continue
                val la = q.d("lat")
                val lo = q.d("lon")
                if (la.isNaN() || lo.isNaN()) continue
                pts.add(Point(la, lo, q.d("alt"), (q.optDouble("time", 0.0) * 1000).toLong()))
            }
            if (pts.isNotEmpty()) out[serial] = Prediction(pts)
        }
        return out
    }

    /** A past flight from /sondes/summary: last received frame, predicted landing and key frames. */
    data class Flight(val sonde: Sonde, val landing: Point?, val frames: List<Point>)

    private fun frame(o: JSONObject): Point? {
        val la = o.d("lat"); val lo = o.d("lon")
        if (la.isNaN() || lo.isNaN()) return null
        return Point(la, lo, o.d("alt"), parseTime(o.optString("datetime", null)))
    }

    private fun summaryChunk(lat: Double, lon: Double, distM: Int, from: Long, to: Long, depth: Int, out: MutableMap<String, Flight>) {
        val f = Instant.ofEpochMilli(from).toString(); val t = Instant.ofEpochMilli(to).toString()
        val root = JSONObject(get("/sondes/summary?lat=$lat&lon=$lon&distance=$distM&from=$f&to=$t&predictions_only=false&limit=200"))
        val r = root.optJSONObject("radiosondes") ?: return
        // 200 is the server cap: split the period so nothing is cut off
        if (r.length() >= 200 && depth < 6 && to - from > 3600_000L) {
            val mid = from + (to - from) / 2
            summaryChunk(lat, lon, distM, from, mid, depth + 1, out)
            summaryChunk(lat, lon, distM, mid, to, depth + 1, out)
            return
        }
        for (serial in r.keys()) {
            val v = r.optJSONObject(serial) ?: continue
            val arr = v.optJSONArray("summary") ?: continue
            if (arr.length() == 0) continue
            val last = arr.optJSONObject(arr.length() - 1) ?: continue
            val sonde = parseSonde(serial, last) ?: continue
            val frames = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let { o -> frame(o) } }
            val pl = v.optJSONObject("predicted_landing")?.let { o ->
                val la = o.d("lat"); val lo = o.d("lon")
                if (la.isNaN() || lo.isNaN()) null else Point(la, lo, o.d("alt"), 0)
            }
            val prev = out[serial]
            if (prev == null || sonde.timeMs > prev.sonde.timeMs) out[serial] = Flight(sonde, pl, frames)
        }
    }

    /**
     * Flights over the last [lastSec] whose last received position OR predicted landing is within
     * [radiusM]. The server matches its area on another point of the flight (launch side), so the
     * query uses a wider area and the result is filtered here; the period is cut in 30-day chunks.
     */
    fun history(lat: Double, lon: Double, radiusM: Int, lastSec: Long): List<Flight> {
        val now = System.currentTimeMillis()
        val start = now - lastSec * 1000
        val out = HashMap<String, Flight>()
        val wide = (radiusM + 150_000).coerceAtMost(700_000)
        var to = now
        while (to > start) {
            val from = maxOf(start, to - 30L * 86400_000L)
            summaryChunk(lat, lon, wide, from, to, 0, out)
            to = from
        }
        val res = FloatArray(1)
        fun near(la: Double, lo: Double): Boolean {
            android.location.Location.distanceBetween(lat, lon, la, lo, res); return res[0] <= radiusM
        }
        return out.values.filter { near(it.sonde.lat, it.sonde.lon) || (it.landing != null && near(it.landing.lat, it.landing.lon)) }
    }

    /** Recovery reports (found / not found) within the area over the last [lastSec] (0 = all time). */
    fun recovered(lat: Double, lon: Double, radiusM: Int, lastSec: Int): Map<String, Recovery> {
        val body = get("/recovered?lat=$lat&lon=$lon&distance=$radiusM&last=$lastSec", 20000).trim()
        val arr: JSONArray = if (body.startsWith("[")) JSONArray(body) else {
            val o = JSONObject(body); val a = JSONArray(); for (k in o.keys()) o.optJSONObject(k)?.let { a.put(it) }; a
        }
        val out = HashMap<String, Recovery>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val serial = o.optString("serial", "")
            if (serial.isBlank()) continue
            val r = Recovery(serial, o.optBoolean("recovered", false), o.optString("recovered_by", ""),
                o.optString("description", ""), parseTime(o.optString("datetime", null)))
            val prev = out[serial]
            if (prev == null || r.timeMs > prev.timeMs) out[serial] = r
        }
        return out
    }
}
