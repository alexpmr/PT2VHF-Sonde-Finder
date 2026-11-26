package com.pt2vhf.sondefinder

import android.app.Activity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Anonymous usage count: at most twice a day the app tells www.pt2vhf.com that this install is in use,
 * with a random ID created on the first run (stored only on the phone), the app version and the platform.
 * No position, account or device identifier is sent. The site stores only a keyed hash of the ID.
 * Same in the site and Google Play builds; can be turned off in About.
 */
object UsagePing {
    private const val URL_ACTIVITY = "https://www.pt2vhf.com/sonde_activity.php"
    private const val INTERVAL_MS = 12 * 3600 * 1000L

    fun enabled(a: Activity) = a.getSharedPreferences("cfg", Activity.MODE_PRIVATE).getBoolean("usagePing", true)

    fun setEnabled(a: Activity, on: Boolean) {
        a.getSharedPreferences("cfg", Activity.MODE_PRIVATE).edit().putBoolean("usagePing", on).apply()
    }

    fun maybeSend(a: Activity) {
        val p = a.getSharedPreferences("cfg", Activity.MODE_PRIVATE)
        if (!p.getBoolean("usagePing", true)) return
        if (System.currentTimeMillis() - p.getLong("usagePingAt", 0) < INTERVAL_MS) return
        val id = p.getString("installId", null) ?: UUID.randomUUID().toString().also { p.edit().putString("installId", it).apply() }
        val version = UpdateManager.installedVersion(a)
        Thread {
            try {
                val body = JSONObject().put("product", "sonde-finder").put("platform", "android")
                    .put("event", "update_check").put("install_id", id).put("version", version).toString()
                val c = URL(URL_ACTIVITY).openConnection() as HttpURLConnection
                try {
                    c.connectTimeout = 8000; c.readTimeout = 10000
                    c.requestMethod = "POST"; c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    c.setRequestProperty("User-Agent", "PT2VHF-Sonde-Finder-Android")
                    c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    if (c.responseCode in 200..299) p.edit().putLong("usagePingAt", System.currentTimeMillis()).apply()
                } finally { c.disconnect() }
            } catch (_: Exception) { /* never disturbs the app; retried on the next start */ }
        }.start()
    }
}
