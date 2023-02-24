/*
 * Copyright (c) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

package com.android.systemui.statusbar.notification

import com.android.systemui.log.dagger.NotificationLockscreenLog
import com.android.systemui.plugins.log.LogBuffer
import com.android.systemui.plugins.log.LogLevel.DEBUG
import com.android.systemui.statusbar.StatusBarState
import javax.inject.Inject

class NotificationWakeUpCoordinatorLogger
@Inject
constructor(@NotificationLockscreenLog private val buffer: LogBuffer) {
    private var lastSetDozeAmountLogWasFractional = false
    private var lastSetDozeAmountLogState = -1
    private var lastSetHardOverride: Float? = null
    private var lastOnDozeAmountChangedLogWasFractional = false

    fun logUpdateDozeAmount(
        inputLinear: Float,
        hardOverride: Float?,
        outputLinear: Float,
        state: Int,
        changed: Boolean,
    ) {
        // Avoid logging on every frame of the animation if important values are not changing
        val isFractional = inputLinear != 1f && inputLinear != 0f
        if (
            lastSetDozeAmountLogWasFractional &&
                isFractional &&
                lastSetDozeAmountLogState == state &&
                lastSetHardOverride == hardOverride
        ) {
            return
        }
        lastSetDozeAmountLogWasFractional = isFractional
        lastSetDozeAmountLogState = state
        lastSetHardOverride = hardOverride

        buffer.log(
            TAG,
            DEBUG,
            {
                double1 = inputLinear.toDouble()
                str1 = hardOverride.toString()
                str2 = outputLinear.toString()
                int1 = state
                bool1 = changed
            },
            {
                "updateDozeAmount() inputLinear=$double1 hardOverride=$str1 outputLinear=$str2" +
                    " state=${StatusBarState.toString(int1)} changed=$bool1"
            }
        )
    }

    fun logSetDozeAmountOverride(dozing: Boolean, source: String) {
        buffer.log(
            TAG,
            DEBUG,
            {
                bool1 = dozing
                str1 = source
            },
            { "setDozeAmountOverride(dozing=$bool1, source=\"$str1\")" }
        )
    }

    fun logMaybeClearHardDozeAmountOverrideHidingNotifs(
        willRemove: Boolean,
        onKeyguard: Boolean,
        dozing: Boolean,
        bypass: Boolean,
        animating: Boolean,
    ) {
        buffer.log(
            TAG,
            DEBUG,
            {
                str1 =
                    "willRemove=$willRemove onKeyguard=$onKeyguard dozing=$dozing" +
                        " bypass=$bypass animating=$animating"
            },
            { "maybeClearHardDozeAmountOverrideHidingNotifs() $str1" }
        )
    }

    fun logOnDozeAmountChanged(linear: Float, eased: Float) {
        // Avoid logging on every frame of the animation when values are fractional
        val isFractional = linear != 1f && linear != 0f
        if (lastOnDozeAmountChangedLogWasFractional && isFractional) return
        lastOnDozeAmountChangedLogWasFractional = isFractional
        buffer.log(
            TAG,
            DEBUG,
            {
                double1 = linear.toDouble()
                str2 = eased.toString()
            },
            { "onDozeAmountChanged(linear=$double1, eased=$str2)" }
        )
    }

    fun logOnStateChanged(newState: Int, storedState: Int) {
        buffer.log(
            TAG,
            DEBUG,
            {
                int1 = newState
                int2 = storedState
            },
            {
                "onStateChanged(newState=${StatusBarState.toString(int1)})" +
                    " stored=${StatusBarState.toString(int2)}"
            }
        )
    }

    fun logClockTransitionAnimationStarting(delayWakeUpAnimation: Boolean) {
        buffer.log(
            TAG,
            DEBUG,
            { bool1 = delayWakeUpAnimation },
            { "clockTransitionAnimationStarting() withDelay=$bool1" }
        )
    }
}

private const val TAG = "NotificationWakeUpCoordinator"
