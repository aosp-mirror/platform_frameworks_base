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

package com.android.systemui.statusbar.phone

import android.util.DisplayMetrics
import android.view.View
import com.android.internal.logging.nano.MetricsProto.MetricsEvent
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.LogLevel
import com.android.systemui.log.dagger.LSShadeTransitionLog
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.android.systemui.statusbar.notification.row.ExpandableView
import javax.inject.Inject

private const val TAG = "LockscreenShadeTransitionController"

class LSShadeTransitionLogger @Inject constructor(
    @LSShadeTransitionLog private val buffer: LogBuffer,
    private val lockscreenGestureLogger: LockscreenGestureLogger,
    private val displayMetrics: DisplayMetrics
) {
    fun logUnSuccessfulDragDown(startingChild: View?) {
        val entry = (startingChild as? ExpandableNotificationRow)?.entry
        buffer.log(TAG, LogLevel.INFO, {
            str1 = entry?.key ?: "no entry"
        }, {
            "Tried to drag down but can't drag down on $str1"
        })
    }

    fun logDragDownAborted() {
        buffer.log(TAG, LogLevel.INFO, {}, {
            "The drag down was aborted and reset to 0f."
        })
    }

    fun logDragDownStarted(startingChild: ExpandableView?) {
        val entry = (startingChild as? ExpandableNotificationRow)?.entry
        buffer.log(TAG, LogLevel.INFO, {
            str1 = entry?.key ?: "no entry"
        }, {
            "The drag down has started on $str1"
        })
    }

    fun logDraggedDownLockDownShade(startingChild: View?) {
        val entry = (startingChild as? ExpandableNotificationRow)?.entry
        buffer.log(TAG, LogLevel.INFO, {
            str1 = entry?.key ?: "no entry"
        }, {
            "Dragged down in locked down shade on $str1"
        })
    }

    fun logDraggedDown(startingChild: View?, dragLengthY: Int) {
        val entry = (startingChild as? ExpandableNotificationRow)?.entry
        buffer.log(TAG, LogLevel.INFO, {
            str1 = entry?.key ?: "no entry"
        }, {
            "Drag down succeeded on $str1"
        })
        // Do logging to event log not just our own buffer
        lockscreenGestureLogger.write(
            MetricsEvent.ACTION_LS_SHADE,
            (dragLengthY / displayMetrics.density).toInt(),
            0 /* velocityDp */)
        lockscreenGestureLogger.log(
            LockscreenGestureLogger.LockscreenUiEvent.LOCKSCREEN_PULL_SHADE_OPEN)
    }

    fun logDragDownAmountReset() {
        buffer.log(TAG, LogLevel.DEBUG, {}, {
            "The drag down amount has been reset to 0f."
        })
    }

    fun logDefaultGoToFullShadeAnimation(delay: Long) {
        buffer.log(TAG, LogLevel.DEBUG, {
            long1 = delay
        }, {
            "Default animation started to full shade with delay $long1"
        })
    }

    fun logTryGoToLockedShade(keyguard: Boolean) {
        buffer.log(TAG, LogLevel.INFO, {
            bool1 = keyguard
        }, {
            "Trying to go to locked shade " + if (bool1) "from keyguard" else "not from keyguard"
        })
    }

    fun logShadeDisabledOnGoToLockedShade() {
        buffer.log(TAG, LogLevel.WARNING, {}, {
            "The shade was disabled when trying to go to the locked shade"
        })
    }

    fun logShowBouncerOnGoToLockedShade() {
        buffer.log(TAG, LogLevel.INFO, {}, {
            "Showing bouncer when trying to go to the locked shade"
        })
    }

    fun logGoingToLockedShade(customAnimationHandler: Boolean) {
        buffer.log(TAG, LogLevel.INFO, {
            bool1 = customAnimationHandler
        }, {
            "Going to locked shade " + if (customAnimationHandler) "with" else "without" +
                " a custom handler"
        })
    }

    fun logOnHideKeyguard() {
        buffer.log(TAG, LogLevel.INFO, {}, {
            "Notified that the keyguard is being hidden"
        })
    }

    fun logPulseExpansionStarted() {
        buffer.log(TAG, LogLevel.INFO, {}, {
            "Pulse Expansion has started"
        })
    }

    fun logPulseExpansionFinished(cancelled: Boolean) {
        if (cancelled) {
            buffer.log(TAG, LogLevel.INFO, {}, {
                "Pulse Expansion is requested to cancel"
            })
        } else {
            buffer.log(TAG, LogLevel.INFO, {}, {
                "Pulse Expansion is requested to finish"
            })
        }
    }

    fun logDragDownAnimation(target: Float) {
        buffer.log(TAG, LogLevel.DEBUG, {
            double1 = target.toDouble()
        }, {
            "Drag down amount animating to " + double1
        })
    }

    fun logAnimationCancelled(isPulse: Boolean) {
        if (isPulse) {
            buffer.log(TAG, LogLevel.DEBUG, {}, {
                "Pulse animation cancelled"
            })
        } else {
            buffer.log(TAG, LogLevel.DEBUG, {}, {
                "drag down animation cancelled"
            })
        }
    }

    fun logDragDownAmountResetWhenFullyCollapsed() {
        buffer.log(TAG, LogLevel.WARNING, {}, {
            "Drag down amount stuck and reset after shade was fully collapsed"
        })
    }

    fun logPulseHeightNotResetWhenFullyCollapsed() {
        buffer.log(TAG, LogLevel.WARNING, {}, {
            "Pulse height stuck and reset after shade was fully collapsed"
        })
    }

    fun logGoingToLockedShadeAborted() {
        buffer.log(TAG, LogLevel.INFO, {}, {
            "Going to the Locked Shade has been aborted"
        })
    }
}
