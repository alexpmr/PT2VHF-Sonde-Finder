package com.pt2vhf.sondefinder

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Updates from the PT2VHF website catalog (same scheme as PT2VHF Speed Alert):
 * the site publishes version, notes, an official download.php link, size and SHA-256.
 * The APK is downloaded, verified (size, SHA-256, signing certificate) and handed to the
 * Android package installer, which always asks the user to confirm.
 */
object UpdateManager {
    private const val CATALOG = "https://www.pt2vhf.com/update.php?product=sonde&platform=android&kind=apk"
    private const val DAY_MS = 24 * 3600 * 1000L

    private data class Pkg(val version: String, val notes: String, val url: String,
                           val sha256: String, val size: Long, val filename: String)

    fun installedVersion(a: Activity): String = try {
        a.packageManager.getPackageInfo(a.packageName, 0).versionName ?: "0.0.0"
    } catch (_: Exception) { "0.0.0" }

    private fun parse(v: String): List<Int>? =
        Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)$").matchEntire(v.trim())?.groupValues?.drop(1)?.map { it.toInt() }

    private fun newer(candidate: String, installed: String): Boolean {
        val a = parse(candidate) ?: return false
        val b = parse(installed) ?: return false
        for (i in 0..2) if (a[i] != b[i]) return a[i] > b[i]
        return false
    }

    private fun connect(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 8000; readTimeout = 20000; instanceFollowRedirects = true; useCaches = false
        setRequestProperty("User-Agent", "PT2VHF-Sonde-Finder-Android")
        setRequestProperty("Accept", "application/json")
    }

    private fun latest(): Pkg? {
        val c = connect(CATALOG)
        val body = try {
            if (c.responseCode != 200) throw IOException("HTTP " + c.responseCode)
            c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
        val doc = JSONObject(body)
        if (doc.optInt("schema") != 1 || doc.optString("product") != "sonde" ||
            doc.optString("status") != "available") return null
        val version = doc.optString("version")
        if (parse(version) == null) return null
        val files = doc.optJSONArray("files") ?: return null
        for (i in 0 until files.length()) {
            val f = files.optJSONObject(i) ?: continue
            if (f.optString("kind") != "apk" || f.optString("platform") != "android") continue
            val name = f.optString("name")
            if (name != "PT2VHF-Sonde-Finder-v$version.apk") continue
            val url = f.optString("url")
            val u = Uri.parse(url)
            if (u.scheme != "https" || u.host != "www.pt2vhf.com" || u.path != "/download.php" ||
                u.getQueryParameter("product") != "sonde" || u.getQueryParameter("file") != name) continue
            val sha = f.optString("sha256").lowercase()
            if (!Regex("^[a-f0-9]{64}$").matches(sha)) continue
            val size = f.optLong("size", 0)
            if (size < 100_000 || size > 100_000_000) continue
            return Pkg(version, doc.optString("notes"), url, sha, size, name)
        }
        return null
    }

    /** Silent check at most once a day (startup); [manual] shows every outcome. */
    fun check(a: Activity, manual: Boolean) {
        val prefs = a.getSharedPreferences("cfg", Activity.MODE_PRIVATE)
        if (!manual && System.currentTimeMillis() - prefs.getLong("lastUpdateCheck", 0) < DAY_MS) return
        val installed = installedVersion(a)
        Thread {
            try {
                val pkg = latest()
                prefs.edit().putLong("lastUpdateCheck", System.currentTimeMillis()).apply()
                a.runOnUiThread {
                    if (a.isFinishing) return@runOnUiThread
                    if (pkg == null || !newer(pkg.version, installed)) {
                        if (manual) AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                            .setTitle(R.string.upd_title)
                            .setMessage(if (pkg == null) a.getString(R.string.upd_none) else a.getString(R.string.upd_latest, installed))
                            .setPositiveButton(android.R.string.ok, null).show()
                    } else {
                        AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                            .setTitle(R.string.upd_new_title)
                            .setMessage(a.getString(R.string.upd_new_msg, pkg.version, installed, pkg.notes))
                            .setNegativeButton(R.string.upd_later, null)
                            .setPositiveButton(R.string.upd_download) { _, _ -> download(a, pkg) }
                            .show()
                    }
                }
            } catch (_: Exception) {
                if (manual) a.runOnUiThread {
                    if (!a.isFinishing) Toast.makeText(a, R.string.upd_unavailable, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    @Suppress("DEPRECATION")
    private fun certDigest(a: Activity, apk: File?): String? {
        val pm = a.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = try {
            if (apk == null) pm.getPackageInfo(a.packageName, flags) else pm.getPackageArchiveInfo(apk.absolutePath, flags)
        } catch (_: Exception) { null } ?: return null
        val sigs = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        val bytes = sigs?.firstOrNull()?.toByteArray() ?: return null
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun download(a: Activity, pkg: Pkg) {
        Toast.makeText(a, R.string.upd_downloading, Toast.LENGTH_LONG).show()
        Thread {
            val target = File(File(a.cacheDir, "updates"), pkg.filename)
            try {
                target.parentFile?.mkdirs()
                val c = connect(pkg.url)
                val md = MessageDigest.getInstance("SHA-256")
                var got = 0L
                try {
                    if (c.responseCode != 200) throw IOException("HTTP " + c.responseCode)
                    c.inputStream.use { inp ->
                        target.outputStream().use { out ->
                            val buf = ByteArray(16384)
                            while (true) {
                                val n = inp.read(buf); if (n < 0) break
                                got += n
                                if (got > pkg.size) throw IOException("size")
                                out.write(buf, 0, n); md.update(buf, 0, n)
                            }
                        }
                    }
                } finally { c.disconnect() }
                if (got != pkg.size) throw IOException("size")
                if (md.digest().joinToString("") { "%02x".format(it) } != pkg.sha256) throw IOException("SHA-256")
                val old = certDigest(a, null)
                val sameKey = old != null && old == certDigest(a, target)
                a.runOnUiThread {
                    if (a.isFinishing) return@runOnUiThread
                    when {
                        !sameKey -> AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                            .setTitle(R.string.upd_sig_title).setMessage(R.string.upd_sig_msg)
                            .setPositiveButton(android.R.string.ok, null).show()
                        !a.packageManager.canRequestPackageInstalls() -> AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                            .setTitle(R.string.upd_perm_title).setMessage(R.string.upd_perm_msg)
                            .setNegativeButton(R.string.cancel, null)
                            .setPositiveButton(R.string.upd_settings) { _, _ ->
                                a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.packageName)))
                            }.show()
                        else -> a.startActivity(Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(FileProvider.getUriForFile(a, a.packageName + ".fileprovider", target),
                                "application/vnd.android.package-archive")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        })
                    }
                }
            } catch (e: Exception) {
                target.delete()
                a.runOnUiThread {
                    if (!a.isFinishing) Toast.makeText(a, a.getString(R.string.upd_failed, e.localizedMessage ?: "?"), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
}
