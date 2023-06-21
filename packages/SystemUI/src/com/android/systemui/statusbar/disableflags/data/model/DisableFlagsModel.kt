/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.statusbar.disableflags.data.model

import android.app.StatusBarManager.DISABLE2_NONE
import android.app.StatusBarManager.DISABLE2_NOTIFICATION_SHADE
import android.app.StatusBarManager.DISABLE2_QUICK_SETTINGS
import android.app.StatusBarManager.DISABLE_NONE
import android.app.StatusBarManager.DISABLE_NOTIFICATION_ALERTS
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.LogLevel
import com.android.systemui.statusbar.disableflags.DisableFlagsLogger

/**
 * Model for the disable flags that come from [IStatusBar].
 *
 * For clients of the disable flags: do *not* refer to the disable integers directly. Instead,
 * re-use or define a helper method that internally processes the flags. (We want to hide the
 * bitwise logic here so no one else has to worry about it.)
 */
data class DisableFlagsModel(
    private val disable1: Int = DISABLE_NONE,
    private val disable2: Int = DISABLE2_NONE,
) {
    /** Returns true if notification alerts are allowed based on the flags. */
    fun areNotificationAlertsEnabled(): Boolean {
        return (disable1 and DISABLE_NOTIFICATION_ALERTS) == 0
    }

    /** Returns true if the shade is allowed based on the flags. */
    fun isShadeEnabled(): Boolean {
        return (disable2 and DISABLE2_NOTIFICATION_SHADE) == 0
    }

    /** Returns true if full quick settings are allowed based on the flags. */
    fun isQuickSettingsEnabled(): Boolean {
        return (disable2 and DISABLE2_QUICK_SETTINGS) == 0
    }

    /** Logs the change to the provided buffer. */
    fun logChange(buffer: LogBuffer, disableFlagsLogger: DisableFlagsLogger) {
        buffer.log(
            TAG,
            LogLevel.INFO,
            {
                int1 = disable1
                int2 = disable2
            },
            {
                disableFlagsLogger.getDisableFlagsString(
                    new = DisableFlagsLogger.DisableState(int1, int2),
                )
            }
        )
    }

    private companion object {
        const val TAG = "DisableFlagsModel"
    }
}
