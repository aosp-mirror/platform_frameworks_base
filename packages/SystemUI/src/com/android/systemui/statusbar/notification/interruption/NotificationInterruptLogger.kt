/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.service.notification.StatusBarNotification
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.LogLevel.DEBUG
import com.android.systemui.log.LogLevel.INFO
import com.android.systemui.log.LogLevel.WARNING
import com.android.systemui.log.dagger.NotificationHeadsUpLog
import com.android.systemui.log.dagger.NotificationLog
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import javax.inject.Inject

class NotificationInterruptLogger @Inject constructor(
    @NotificationLog val notifBuffer: LogBuffer,
    @NotificationHeadsUpLog val hunBuffer: LogBuffer
) {
    fun logHeadsUpFeatureChanged(useHeadsUp: Boolean) {
        hunBuffer.log(TAG, INFO, {
            bool1 = useHeadsUp
        }, {
            "heads up is enabled=$bool1"
        })
    }

    fun logWillDismissAll() {
        hunBuffer.log(TAG, INFO, {
        }, {
            "dismissing any existing heads up notification on disable event"
        })
    }

    fun logNoBubbleNotAllowed(sbn: StatusBarNotification) {
        notifBuffer.log(TAG, DEBUG, {
            str1 = sbn.key
        }, {
            "No bubble up: not allowed to bubble: $str1"
        })
    }

    fun logNoBubbleNoMetadata(sbn: StatusBarNotification) {
        notifBuffer.log(TAG, DEBUG, {
            str1 = sbn.key
        }, {
            "No bubble up: notification: $str1 doesn't have valid metadata"
        })
    }

    fun logNoHeadsUpFeatureDisabled() {
        hunBuffer.log(TAG, DEBUG, {
        }, {
            "No heads up: no huns"
        })
    }

    fun logNoHeadsUpPackageSnoozed(sbn: StatusBarNotification) {
        hunBuffer.log(TAG, DEBUG, {
            str1 = sbn.key
        }, {
            "No alerting: snoozed package: $str1"
        })
    }

    fun logNoHeadsUpAlreadyBubbled(sbn: StatusBarNotification) {
        hunBuffer.log(TAG, DEBUG, {
            str1 = sbn.key
        }, {
            "No heads up: in unlocked shade where notification is shown as a bubble: $str1"
        })
    }

    fun logNoHeadsUpSuppressedByDnd(sbn: StatusBarNotification) {
        hunBuffer.log(TAG, DEBUG, {
            str1 = sbn.key
        }, {
            "No heads up: suppressed by DND: $str1"
        })
    }

    fun logNoHeadsUpNotImportant(sbn: StatusBarNotification) {
        hunBuffer.log(TAG, DEBUG, {
            str1 = sbn.key
        }, {
            "No heads up: unimportant notification: $str1"
        })
    }

    fun logNoHeadsUpNotInUse(sbn: StatusBarNotification) {
        hunBuffer.log(TAG, DEBUG, {
            str1 = sbn.key
        }, {
            "No heads up: not in use: $str1"
        })
    }

    fun logNoHeadsUpSuppressedBy(
        sbn: StatusBarNotification,
        suppressor: NotificationInterruptSuppressor
    ) {
        hunBuffer.log(TAG, DEBUG, {
            str1 = sbn.key
            str2 = suppressor.name
        }, {
            "No heads up: aborted by suppressor: $str2 sbnKey=$str1"
        })
    }

    fun logHeadsUp(sbn: StatusBarNotification) {
        hunBuffer.log(TAG, DEBUG, {
            str1 = sbn.key
        }, {
            "Heads up: $str1"
        })
    }

    fun logNoAlertingFilteredOut(sbn: StatusBarNotification) {
        hunBuffer.log(TAG, DEBUG, {
            str1 = sbn.key
        }, {
            "No alerting: filtered notification: $str1"
        })
    }

    fun logNoAlertingGroupAlertBehavior(sbn: StatusBarNotification) {
        hunBuffer.log(TAG, DEBUG, {
            str1 = sbn.key
        }, {
            "No alerting: suppressed due to group alert behavior: $str1"
        })
    }

    fun logNoAlertingSuppressedBy(
        sbn: StatusBarNotification,
        suppressor: NotificationInterruptSuppressor,
        awake: Boolean
    ) {
        hunBuffer.log(TAG, DEBUG, {
            str1 = sbn.key
            str2 = suppressor.name
            bool1 = awake
        }, {
            "No alerting: aborted by suppressor: $str2 awake=$bool1 sbnKey=$str1"
        })
    }

    fun logNoAlertingRecentFullscreen(sbn: StatusBarNotification) {
        hunBuffer.log(TAG, DEBUG, {
            str1 = sbn.key
        }, {
            "No alerting: recent fullscreen: $str1"
        })
    }

    fun logNoPulsingSettingDisabled(sbn: StatusBarNotification) {
        hunBuffer.log(TAG, DEBUG, {
            str1 = sbn.key
        }, {
            "No pulsing: disabled by setting: $str1"
        })
    }

    fun logNoPulsingBatteryDisabled(sbn: StatusBarNotification) {
        hunBuffer.log(TAG, DEBUG, {
            str1 = sbn.key
        }, {
            "No pulsing: disabled by battery saver: $str1"
        })
    }

    fun logNoPulsingNoAlert(sbn: StatusBarNotification) {
        hunBuffer.log(TAG, DEBUG, {
            str1 = sbn.key
        }, {
            "No pulsing: notification shouldn't alert: $str1"
        })
    }

    fun logNoPulsingNoAmbientEffect(sbn: StatusBarNotification) {
        hunBuffer.log(TAG, DEBUG, {
            str1 = sbn.key
        }, {
            "No pulsing: ambient effect suppressed: $str1"
        })
    }

    fun logNoPulsingNotImportant(sbn: StatusBarNotification) {
        hunBuffer.log(TAG, DEBUG, {
            str1 = sbn.key
        }, {
            "No pulsing: not important enough: $str1"
        })
    }

    fun logPulsing(sbn: StatusBarNotification) {
        hunBuffer.log(TAG, DEBUG, {
            str1 = sbn.key
        }, {
            "Pulsing: $str1"
        })
    }

    fun logNoFullscreen(entry: NotificationEntry, reason: String) {
        hunBuffer.log(TAG, DEBUG, {
            str1 = entry.key
            str2 = reason
        }, {
            "No FullScreenIntent: $str2: $str1"
        })
    }

    fun logNoFullscreenWarning(entry: NotificationEntry, reason: String) {
        hunBuffer.log(TAG, WARNING, {
            str1 = entry.key
            str2 = reason
        }, {
            "No FullScreenIntent: WARNING: $str2: $str1"
        })
    }

    fun logFullscreen(entry: NotificationEntry, reason: String) {
        hunBuffer.log(TAG, DEBUG, {
            str1 = entry.key
            str2 = reason
        }, {
            "FullScreenIntent: $str2: $str1"
        })
    }

    fun keyguardHideNotification(key: String) {
        hunBuffer.log(TAG, DEBUG, {
            str1 = key
        }, {
            "Keyguard Hide Notification: $str1"
        })
    }
}

private const val TAG = "InterruptionStateProvider"
