package com.pt2vhf.sondefinder

import android.app.Activity
import android.app.AlertDialog
import android.widget.Toast
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability

/**
 * Google Play build: Play forbids self-updates, so the app uses Play In-App Updates (flexible):
 * Play downloads the new version in the background and the app offers to restart into it.
 */
object StoreUpdate {
    private const val REQ = 4711
    private const val DAY_MS = 24 * 3600 * 1000L
    private var listening = false

    fun check(a: Activity, manual: Boolean) {
        val prefs = a.getSharedPreferences("cfg", Activity.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!manual && now - prefs.getLong("playUpdCheck", 0L) < DAY_MS) return
        prefs.edit().putLong("playUpdCheck", now).apply()
        val mgr = AppUpdateManagerFactory.create(a)
        if (!listening) {
            listening = true
            mgr.registerListener(object : InstallStateUpdatedListener {
                override fun onStateUpdate(s: com.google.android.play.core.install.InstallState) {
                    if (s.installStatus() == InstallStatus.DOWNLOADED) {
                        mgr.unregisterListener(this); listening = false
                        if (!a.isFinishing) askRestart(a)
                    }
                }
            })
        }
        mgr.appUpdateInfo.addOnSuccessListener { info ->
            if (a.isFinishing) return@addOnSuccessListener
            if (info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)) {
                try {
                    @Suppress("DEPRECATION")
                    mgr.startUpdateFlowForResult(info, a, AppUpdateOptions.defaultOptions(AppUpdateType.FLEXIBLE), REQ)
                } catch (_: Exception) {}
            } else if (info.installStatus() == InstallStatus.DOWNLOADED) {
                askRestart(a)
            } else if (manual) {
                Toast.makeText(a, a.getString(R.string.upd_latest, UpdateManager.installedVersion(a)), Toast.LENGTH_LONG).show()
            }
        }.addOnFailureListener {
            if (manual) Toast.makeText(a, R.string.upd_play_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    /** An update downloaded while the app was in the background is offered again on return. */
    fun onResume(a: Activity) {
        AppUpdateManagerFactory.create(a).appUpdateInfo.addOnSuccessListener { info ->
            if (info.installStatus() == InstallStatus.DOWNLOADED && !a.isFinishing) askRestart(a)
        }
    }

    private var asking = false
    private fun askRestart(a: Activity) {
        if (asking) return
        asking = true
        AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(R.string.upd_play_ready_title)
            .setMessage(R.string.upd_play_ready_msg)
            .setPositiveButton(R.string.upd_play_restart) { _, _ -> AppUpdateManagerFactory.create(a).completeUpdate() }
            .setNegativeButton(R.string.upd_play_later, null)
            .setOnDismissListener { asking = false }
            .show()
    }
}
