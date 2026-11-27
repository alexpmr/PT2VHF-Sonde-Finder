package com.pt2vhf.sondefinder

import android.app.Activity

/** Site APK: updates come from www.pt2vhf.com (UpdateManager). */
object StoreUpdate {
    fun check(a: Activity, manual: Boolean) = UpdateManager.check(a, manual)
    fun onResume(a: Activity) {}
}
