package com.android.systemui.utils

import android.view.WindowManagerGlobal
import javax.inject.Inject

/** Interface to talk to [WindowManagerGlobal] */
class GlobalWindowManager @Inject constructor() {
    /**
     * Sends a trim memory command to [WindowManagerGlobal].
     *
     * @param level One of levels from [ComponentCallbacks2] starting with TRIM_
     */
    fun trimMemory(level: Int) {
        WindowManagerGlobal.getInstance().trimMemory(level)
    }
}
