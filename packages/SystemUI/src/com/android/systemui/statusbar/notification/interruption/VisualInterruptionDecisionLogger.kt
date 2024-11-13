/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.notification.interruption

import android.util.Log
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel.DEBUG
import com.android.systemui.log.core.LogLevel.INFO
import com.android.systemui.log.core.LogLevel.WARNING
import com.android.systemui.log.dagger.NotificationInterruptLog
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.interruption.VisualInterruptionDecisionProvider.FullScreenIntentDecision
import com.android.systemui.statusbar.notification.logKey
import com.android.systemui.util.Compile
import javax.inject.Inject

class VisualInterruptionDecisionLogger
@Inject
constructor(@NotificationInterruptLog val buffer: LogBuffer) {

    val spew: Boolean = Compile.IS_DEBUG && Log.isLoggable(TAG, Log.VERBOSE)

    fun logHeadsUpFeatureChanged(isEnabled: Boolean) {
        buffer.log(
            TAG,
            INFO,
            { bool1 = isEnabled },
            { "HUN feature is now ${if (bool1) "enabled" else "disabled"}" }
        )
    }

    fun logWillDismissAll() {
        buffer.log(TAG, INFO, {}, { "dismissing all HUNs since feature was disabled" })
    }

    fun logDecision(
        type: String,
        entry: NotificationEntry,
        decision: VisualInterruptionDecisionProvider.Decision
    ) {
        buffer.log(
            TAG,
            DEBUG,
            {
                str1 = type
                bool1 = decision.shouldInterrupt
                str2 = decision.logReason
                str3 = entry.logKey
            },
            {
                val outcome = if (bool1) "allowed" else "suppressed"
                "$str1 $outcome: $str2 (key=$str3)"
            }
        )
    }

    fun logFullScreenIntentDecision(
        entry: NotificationEntry,
        decision: FullScreenIntentDecision,
        warning: Boolean
    ) {
        buffer.log(
            TAG,
            if (warning) WARNING else DEBUG,
            {
                bool1 = decision.shouldInterrupt
                bool2 = decision.wouldInterruptWithoutDnd
                str1 = decision.logReason
                str2 = entry.logKey
            },
            {
                val outcome =
                    when {
                        bool1 -> "allowed"
                        bool2 -> "suppressed only by DND"
                        else -> "suppressed"
                    }
                "FSI $outcome: $str1 (key=$str2)"
            }
        )
    }

    fun logAvalancheAllow(info: String) {
        buffer.log(
            TAG,
            INFO,
            { str1 = info },
            { "AvalancheSuppressor: $str1" }
        )
    }

    fun logCooldownSetting(isEnabled: Boolean) {
        buffer.log(
            TAG,
            INFO,
            { bool1 = isEnabled },
            { "Cooldown enabled: $bool1" }
        )
    }
}

private const val TAG = "VisualInterruptionDecisionProvider"
