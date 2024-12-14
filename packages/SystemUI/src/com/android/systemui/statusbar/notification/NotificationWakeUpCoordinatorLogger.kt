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

import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel.DEBUG
import com.android.systemui.log.dagger.NotificationLockscreenLog
import com.android.systemui.statusbar.StatusBarState
import javax.inject.Inject

class NotificationWakeUpCoordinatorLogger
@Inject
constructor(@NotificationLockscreenLog private val buffer: LogBuffer) {
    private var allowThrottle = true
    private var lastSetDozeAmountLogInputWasFractional = false
    private var lastSetDozeAmountLogDelayWasFractional = false
    private var lastSetDozeAmountLogState = -1
    private var lastSetHardOverride: Float? = null
    private var lastOnDozeAmountChangedLogWasFractional = false
    private var lastSetDelayDozeAmountOverrideLogWasFractional = false
    private var lastSetVisibilityAmountLogWasFractional = false
    private var lastSetHideAmountLogWasFractional = false
    private var lastSetHideAmount = -1f

    fun logUpdateDozeAmount(
        inputLinear: Float,
        delayLinear: Float,
        hardOverride: Float?,
        outputLinear: Float,
        state: Int,
        changed: Boolean,
    ) {
        // Avoid logging on every frame of the animation if important values are not changing
        val isInputFractional = inputLinear != 1f && inputLinear != 0f
        val isDelayFractional = delayLinear != 1f && delayLinear != 0f
        if (
            (isInputFractional || isDelayFractional) &&
                lastSetDozeAmountLogInputWasFractional == isInputFractional &&
                lastSetDozeAmountLogDelayWasFractional == isDelayFractional &&
                lastSetDozeAmountLogState == state &&
                lastSetHardOverride == hardOverride &&
                allowThrottle
        ) {
            return
        }
        lastSetDozeAmountLogInputWasFractional = isInputFractional
        lastSetDozeAmountLogDelayWasFractional = isDelayFractional
        lastSetDozeAmountLogState = state
        lastSetHardOverride = hardOverride

        buffer.log(
            TAG,
            DEBUG,
            {
                double1 = inputLinear.toDouble()
                str1 = hardOverride.toString()
                str2 = outputLinear.toString()
                str3 = delayLinear.toString()
                int1 = state
                bool1 = changed
            },
            {
                "updateDozeAmount() inputLinear=$double1 delayLinear=$str3" +
                    " hardOverride=$str1 outputLinear=$str2" +
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
        idleOnCommunal: Boolean,
        animating: Boolean,
    ) {
        buffer.log(
            TAG,
            DEBUG,
            {
                str1 =
                    "willRemove=$willRemove onKeyguard=$onKeyguard dozing=$dozing" +
                        " bypass=$bypass animating=$animating idleOnCommunal=$idleOnCommunal"
            },
            { "maybeClearHardDozeAmountOverrideHidingNotifs() $str1" }
        )
    }

    fun logOnDozeAmountChanged(linear: Float, eased: Float) {
        // Avoid logging on every frame of the animation when values are fractional
        val isFractional = linear != 1f && linear != 0f
        if (lastOnDozeAmountChangedLogWasFractional && isFractional && allowThrottle) return
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

    fun logSetDelayDozeAmountOverride(linear: Float) {
        // Avoid logging on every frame of the animation when values are fractional
        val isFractional = linear != 1f && linear != 0f
        if (lastSetDelayDozeAmountOverrideLogWasFractional && isFractional && allowThrottle) return
        lastSetDelayDozeAmountOverrideLogWasFractional = isFractional
        buffer.log(
            TAG,
            DEBUG,
            { double1 = linear.toDouble() },
            { "setDelayDozeAmountOverride($double1)" }
        )
    }

    fun logSetVisibilityAmount(linear: Float) {
        // Avoid logging on every frame of the animation when values are fractional
        val isFractional = linear != 1f && linear != 0f
        if (lastSetVisibilityAmountLogWasFractional && isFractional && allowThrottle) return
        lastSetVisibilityAmountLogWasFractional = isFractional
        buffer.log(TAG, DEBUG, { double1 = linear.toDouble() }, { "setVisibilityAmount($double1)" })
    }

    fun logSetHideAmount(linear: Float) {
        // Avoid logging the same value repeatedly
        if (lastSetHideAmount == linear && allowThrottle) return
        lastSetHideAmount = linear
        // Avoid logging on every frame of the animation when values are fractional
        val isFractional = linear != 1f && linear != 0f
        if (lastSetHideAmountLogWasFractional && isFractional && allowThrottle) return
        lastSetHideAmountLogWasFractional = isFractional
        buffer.log(TAG, DEBUG, { double1 = linear.toDouble() }, { "setHideAmount($double1)" })
    }

    fun logStartDelayedDozeAmountAnimation(alreadyRunning: Boolean) {
        buffer.log(
            TAG,
            DEBUG,
            { bool1 = alreadyRunning },
            { "startDelayedDozeAmountAnimation() alreadyRunning=$bool1" }
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

    fun logOnPanelExpansionChanged(
        fraction: Float,
        wasCollapsedEnoughToHide: Boolean,
        isCollapsedEnoughToHide: Boolean,
        couldShowPulsingHuns: Boolean,
        canShowPulsingHuns: Boolean
    ) {
        buffer.log(
            TAG,
            DEBUG,
            {
                double1 = fraction.toDouble()
                bool1 = wasCollapsedEnoughToHide
                bool2 = isCollapsedEnoughToHide
                bool3 = couldShowPulsingHuns
                bool4 = canShowPulsingHuns
            },
            {
                "onPanelExpansionChanged($double1):" +
                    " collapsedEnoughToHide: $bool1 -> $bool2," +
                    " canShowPulsingHuns: $bool3 -> $bool4"
            }
        )
    }

    fun logSetWakingUp(wakingUp: Boolean, requestDelayedAnimation: Boolean) {
        buffer.log(
            TAG,
            DEBUG,
            {
                bool1 = wakingUp
                bool2 = requestDelayedAnimation
            },
            { "setWakingUp(wakingUp=$bool1, requestDelayedAnimation=$bool2)" }
        )
    }

    fun logDelayingClockWakeUpAnimation(delayingAnimation: Boolean) {
        buffer.log(
            TAG,
            DEBUG,
            { bool1 = delayingAnimation },
            { "logDelayingClockWakeUpAnimation($bool1)" }
        )
    }
}

private const val TAG = "NotificationWakeUpCoordinator"
