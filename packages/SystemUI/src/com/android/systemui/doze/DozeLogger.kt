/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.doze

import android.view.Display
import com.android.systemui.doze.DozeLog.Reason
import com.android.systemui.doze.DozeLog.reasonToString
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.LogLevel.DEBUG
import com.android.systemui.log.LogLevel.ERROR
import com.android.systemui.log.LogLevel.INFO
import com.android.systemui.log.dagger.DozeLog
import com.android.systemui.statusbar.policy.DevicePostureController
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/** Interface for logging messages to the [DozeLog]. */
class DozeLogger @Inject constructor(
    @DozeLog private val buffer: LogBuffer
) {
    fun logPickupWakeup(isWithinVibrationThreshold: Boolean) {
        buffer.log(TAG, DEBUG, {
            bool1 = isWithinVibrationThreshold
        }, {
            "PickupWakeup withinVibrationThreshold=$bool1"
        })
    }

    fun logPulseStart(@Reason reason: Int) {
        buffer.log(TAG, INFO, {
            int1 = reason
        }, {
            "Pulse start, reason=${reasonToString(int1)}"
        })
    }

    fun logPulseFinish() {
        buffer.log(TAG, INFO, {}, { "Pulse finish" })
    }

    fun logNotificationPulse() {
        buffer.log(TAG, INFO, {}, { "Notification pulse" })
    }

    fun logDozing(isDozing: Boolean) {
        buffer.log(TAG, INFO, {
            bool1 = isDozing
        }, {
            "Dozing=$bool1"
        })
    }

    fun logDozingChanged(isDozing: Boolean) {
        buffer.log(TAG, INFO, {
            bool1 = isDozing
        }, {
            "Dozing changed dozing=$bool1"
        })
    }

    fun logPowerSaveChanged(powerSaveActive: Boolean, nextState: DozeMachine.State) {
        buffer.log(TAG, INFO, {
            bool1 = powerSaveActive
            str1 = nextState.name
        }, {
            "Power save active=$bool1 nextState=$str1"
        })
    }

    fun logAlwaysOnSuppressedChange(isAodSuppressed: Boolean, nextState: DozeMachine.State) {
        buffer.log(TAG, INFO, {
            bool1 = isAodSuppressed
            str1 = nextState.name
        }, {
            "Always on (AOD) suppressed changed, suppressed=$bool1 nextState=$str1"
        })
    }

    fun logFling(
        expand: Boolean,
        aboveThreshold: Boolean,
        thresholdNeeded: Boolean,
        screenOnFromTouch: Boolean
    ) {
        buffer.log(TAG, DEBUG, {
            bool1 = expand
            bool2 = aboveThreshold
            bool3 = thresholdNeeded
            bool4 = screenOnFromTouch
        }, {
            "Fling expand=$bool1 aboveThreshold=$bool2 thresholdNeeded=$bool3 " +
                "screenOnFromTouch=$bool4"
        })
    }

    fun logEmergencyCall() {
        buffer.log(TAG, INFO, {}, { "Emergency call" })
    }

    fun logKeyguardBouncerChanged(isShowing: Boolean) {
        buffer.log(TAG, INFO, {
            bool1 = isShowing
        }, {
            "Keyguard bouncer changed, showing=$bool1"
        })
    }

    fun logScreenOn(isPulsing: Boolean) {
        buffer.log(TAG, INFO, {
            bool1 = isPulsing
        }, {
            "Screen on, pulsing=$bool1"
        })
    }

    fun logScreenOff(why: Int) {
        buffer.log(TAG, INFO, {
            int1 = why
        }, {
            "Screen off, why=$int1"
        })
    }

    fun logMissedTick(delay: String) {
        buffer.log(TAG, ERROR, {
            str1 = delay
        }, {
            "Missed AOD time tick by $str1"
        })
    }

    fun logTimeTickScheduled(whenAt: Long, triggerAt: Long) {
        buffer.log(TAG, DEBUG, {
            long1 = whenAt
            long2 = triggerAt
        }, {
            "Time tick scheduledAt=${DATE_FORMAT.format(Date(long1))} " +
                "triggerAt=${DATE_FORMAT.format(Date(long2))}"
        })
    }

    fun logKeyguardVisibilityChange(isShowing: Boolean) {
        buffer.log(TAG, INFO, {
            bool1 = isShowing
        }, {
            "Keyguard visibility change, isShowing=$bool1"
        })
    }

    fun logDozeStateChanged(state: DozeMachine.State) {
        buffer.log(TAG, INFO, {
            str1 = state.name
        }, {
            "Doze state changed to $str1"
        })
    }

    fun logStateChangedSent(state: DozeMachine.State) {
        buffer.log(TAG, INFO, {
            str1 = state.name
        }, {
            "Doze state sent to all DozeMachineParts stateSent=$str1"
        })
    }

    fun logDisplayStateDelayedByUdfps(delayedDisplayState: Int) {
        buffer.log(TAG, INFO, {
            str1 = Display.stateToString(delayedDisplayState)
        }, {
            "Delaying display state change to: $str1 due to UDFPS activity"
        })
    }

    fun logDisplayStateChanged(displayState: Int) {
        buffer.log(TAG, INFO, {
            str1 = Display.stateToString(displayState)
        }, {
            "Display state changed to $str1"
        })
    }

    fun logWakeDisplay(isAwake: Boolean, @Reason reason: Int) {
        buffer.log(TAG, DEBUG, {
            bool1 = isAwake
            int1 = reason
        }, {
            "Display wakefulness changed, isAwake=$bool1, reason=${reasonToString(int1)}"
        })
    }

    fun logProximityResult(isNear: Boolean, millis: Long, @Reason reason: Int) {
        buffer.log(TAG, DEBUG, {
            bool1 = isNear
            long1 = millis
            int1 = reason
        }, {
            "Proximity result reason=${reasonToString(int1)} near=$bool1 millis=$long1"
        })
    }

    fun logPostureChanged(posture: Int, partUpdated: String) {
        buffer.log(TAG, INFO, {
            int1 = posture
            str1 = partUpdated
        }, {
            "Posture changed, posture=${DevicePostureController.devicePostureToString(int1)}" +
                " partUpdated=$str1"
        })
    }

    fun logPulseDropped(pulsePending: Boolean, state: DozeMachine.State, blocked: Boolean) {
        buffer.log(TAG, INFO, {
            bool1 = pulsePending
            str1 = state.name
            bool2 = blocked
        }, {
            "Pulse dropped, pulsePending=$bool1 state=$str1 blocked=$bool2"
        })
    }

    fun logSensorEventDropped(sensorEvent: Int, reason: String) {
        buffer.log(TAG, INFO, {
            int1 = sensorEvent
            str1 = reason
        }, {
            "SensorEvent [$int1] dropped, reason=$str1"
        })
    }

    fun logPulseDropped(reason: String) {
        buffer.log(TAG, INFO, {
            str1 = reason
        }, {
            "Pulse dropped, why=$str1"
        })
    }

    fun logPulseTouchDisabledByProx(disabled: Boolean) {
        buffer.log(TAG, DEBUG, {
            bool1 = disabled
        }, {
            "Pulse touch modified by prox, disabled=$bool1"
        })
    }

    fun logSensorTriggered(@Reason reason: Int) {
        buffer.log(TAG, DEBUG, {
            int1 = reason
        }, {
            "Sensor triggered, type=${reasonToString(int1)}"
        })
    }

    fun logAlwaysOnSuppressed(state: DozeMachine.State, reason: String) {
        buffer.log(TAG, INFO, {
            str1 = state.name
            str2 = reason
        }, {
            "Always-on state suppressed, suppressed state=$str1 reason=$str2"
        })
    }

    fun logImmediatelyEndDoze(reason: String) {
        buffer.log(TAG, INFO, {
            str1 = reason
        }, {
            "Doze immediately ended due to $str1"
        })
    }

    fun logDozeScreenBrightness(brightness: Int) {
        buffer.log(TAG, INFO, {
            int1 = brightness
        }, {
            "Doze screen brightness set, brightness=$int1"
        })
    }

    fun logSetAodDimmingScrim(scrimOpacity: Long) {
        buffer.log(TAG, INFO, {
            long1 = scrimOpacity
        }, {
            "Doze aod dimming scrim opacity set, opacity=$long1"
        })
    }

    fun logCarModeEnded() {
        buffer.log(TAG, INFO, {}, {
            "Doze car mode ended"
        })
    }

    fun logCarModeStarted() {
        buffer.log(TAG, INFO, {}, {
            "Doze car mode started"
        })
    }
}

private const val TAG = "DozeLog"

val DATE_FORMAT = SimpleDateFormat("MM-dd HH:mm:ss.S", Locale.US)
