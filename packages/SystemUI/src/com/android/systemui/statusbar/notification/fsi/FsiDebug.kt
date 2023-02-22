package com.android.systemui.statusbar.notification.fsi

class FsiDebug {

    companion object {
        private const val debugTag = "FsiDebug"
        private const val debug = true

        fun log(s: Any) {
            if (!debug) {
                return
            }
            android.util.Log.d(debugTag, "$s")
        }
    }
}