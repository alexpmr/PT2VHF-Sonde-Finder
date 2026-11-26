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

    private fun get(path: String): String {
        val c = URL(BASE + path).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 60000
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", "application/json")
        try {
            val code = c.responseCode
            if (code !in 200..299) throw RuntimeException("HTTP $code")
            return c.inputStream.bufferedReader().use { it.readText() }
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
        )
    }

    /** Latest telemetry of every sonde heard within [radiusM] of the point in the last [lastSec]. */
    fun nearby(lat: Double, lon: Double, radiusM: Int, lastSec: Int): List<Sonde> {
        val body = get("/sondes?lat=$lat&lon=$lon&distance=$radiusM&last=$lastSec")
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
        val root = JSONObject(get("/sondes/telemetry?duration=$duration&serial=$s"))
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
    fun prediction(serial: String): Prediction? {
        val s = URLEncoder.encode(serial, "UTF-8")
        val arr = JSONArray(get("/predictions?vehicles=$s"))
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            if (p.optString("vehicle") != serial) continue
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
            if (pts.isNotEmpty()) return Prediction(pts)
        }
        return null
    }

    /** Recovery reports (found / not found) within the area over the last [lastSec] (0 = all time). */
    fun recovered(lat: Double, lon: Double, radiusM: Int, lastSec: Int): Map<String, Recovery> {
        val body = get("/recovered?lat=$lat&lon=$lon&distance=$radiusM&last=$lastSec").trim()
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
