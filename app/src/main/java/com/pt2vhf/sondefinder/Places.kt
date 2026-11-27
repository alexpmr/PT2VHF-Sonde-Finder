package com.pt2vhf.sondefinder

import android.content.Context
import android.location.Geocoder
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Street, neighbourhood and city of a point (landing place). Android's Geocoder first (free, needs
 * internet); Nominatim/OpenStreetMap as fallback (max 1 request/s, so callers only ask when the
 * point changes). Blocking: call off the UI thread.
 */
object Places {
    fun lookup(ctx: Context, lat: Double, lon: Double): String? =
        try { fromGeocoder(ctx, lat, lon) } catch (_: Exception) { null }
            ?: try { fromNominatim(lat, lon) } catch (_: Exception) { null }

    /** "Rua X, 123 · Bairro · Cidade"; parts that are missing are skipped. */
    fun join(street: String?, number: String?, district: String?, city: String?): String? {
        val road = listOfNotNull(street?.trim()?.ifBlank { null }, number?.trim()?.ifBlank { null }).joinToString(", ")
        val parts = listOf(road, district?.trim().orEmpty(), city?.trim().orEmpty()).filter { it.isNotBlank() }.distinct()
        return parts.joinToString(" · ").ifBlank { null }
    }

    @Suppress("DEPRECATION")
    private fun fromGeocoder(ctx: Context, lat: Double, lon: Double): String? {
        if (!Geocoder.isPresent()) return null
        val a = Geocoder(ctx, Locale.getDefault()).getFromLocation(lat, lon, 1)?.firstOrNull() ?: return null
        return join(a.thoroughfare, a.subThoroughfare, a.subLocality, a.locality ?: a.subAdminArea)
    }

    private fun fromNominatim(lat: Double, lon: Double): String? {
        val url = String.format(Locale.US,
            "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=18&addressdetails=1&lat=%.6f&lon=%.6f", lat, lon)
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 10000
            setRequestProperty("User-Agent", "PT2VHF-Sonde-Finder-Android")
            setRequestProperty("Accept-Language", Locale.getDefault().toLanguageTag())
        }
        val body = try {
            if (c.responseCode != 200) return null
            c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
        val a = JSONObject(body).optJSONObject("address") ?: return null
        fun s(vararg k: String) = k.firstNotNullOfOrNull { a.optString(it).ifBlank { null } }
        return join(s("road", "pedestrian", "path", "track"), s("house_number"),
            s("suburb", "neighbourhood", "quarter", "hamlet"), s("city", "town", "village", "municipality"))
    }
}
