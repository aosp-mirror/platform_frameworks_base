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
import com.android.systemui.log.core.LogLevel.DEBUG
import com.android.systemui.log.core.LogLevel.ERROR
import com.android.systemui.log.core.LogLevel.INFO
import com.android.systemui.log.dagger.DozeLog
import com.android.systemui.statusbar.policy.DevicePostureController
import com.google.errorprone.annotations.CompileTimeConstant
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/** Interface for logging messages to the [DozeLog]. */
class DozeLogger @Inject constructor(@DozeLog private val buffer: LogBuffer) {
    fun logPickupWakeup(isWithinVibrationThreshold: Boolean) {
        buffer.log(
            TAG,
            DEBUG,
            { bool1 = isWithinVibrationThreshold },
            { "PickupWakeup withinVibrationThreshold=$bool1" }
        )
    }

    fun logPulseStart(@Reason reason: Int) {
        buffer.log(TAG, INFO, { int1 = reason }, { "Pulse start, reason=${reasonToString(int1)}" })
    }

    fun logPulseFinish() {
        buffer.log(TAG, INFO, {}, { "Pulse finish" })
    }

    fun logNotificationPulse() {
        buffer.log(TAG, INFO, {}, { "Notification pulse" })
    }

    fun logDozing(isDozing: Boolean) {
        buffer.log(TAG, INFO, { bool1 = isDozing }, { "Dozing=$bool1" })
    }

    fun logDozingChanged(isDozing: Boolean) {
        buffer.log(TAG, INFO, { bool1 = isDozing }, { "Dozing changed dozing=$bool1" })
    }

    fun logPowerSaveChanged(powerSaveActive: Boolean, nextState: DozeMachine.State) {
        buffer.log(
            TAG,
            INFO,
            {
                bool1 = powerSaveActive
                str1 = nextState.name
            },
            { "Power save active=$bool1 nextState=$str1" }
        )
    }

    fun logAlwaysOnSuppressedChange(isAodSuppressed: Boolean, nextState: DozeMachine.State) {
        buffer.log(
            TAG,
            INFO,
            {
                bool1 = isAodSuppressed
                str1 = nextState.name
            },
            { "Always on (AOD) suppressed changed, suppressed=$bool1 nextState=$str1" }
        )
    }

    fun logFling(expand: Boolean, aboveThreshold: Boolean, screenOnFromTouch: Boolean) {
        buffer.log(
            TAG,
            DEBUG,
            {
                bool1 = expand
                bool2 = aboveThreshold
                bool4 = screenOnFromTouch
            },
            {
                "Fling expand=$bool1 aboveThreshold=$bool2 thresholdNeeded=$bool3 " +
                    "screenOnFromTouch=$bool4"
            }
        )
    }

    fun logEmergencyCall() {
        buffer.log(TAG, INFO, {}, { "Emergency call" })
    }

    fun logKeyguardBouncerChanged(isShowing: Boolean) {
        buffer.log(TAG, INFO, { bool1 = isShowing }, { "Keyguard bouncer changed, showing=$bool1" })
    }

    fun logScreenOn(isPulsing: Boolean) {
        buffer.log(TAG, INFO, { bool1 = isPulsing }, { "Screen on, pulsing=$bool1" })
    }

    fun logScreenOff(why: Int) {
        buffer.log(TAG, INFO, { int1 = why }, { "Screen off, why=$int1" })
    }

    fun logMissedTick(delay: String) {
        buffer.log(TAG, ERROR, { str1 = delay }, { "Missed AOD time tick by $str1" })
    }

    fun logTimeTickScheduled(whenAt: Long, triggerAt: Long) {
        buffer.log(
            TAG,
            DEBUG,
            {
                long1 = whenAt
                long2 = triggerAt
            },
            {
                "Time tick scheduledAt=${DATE_FORMAT.format(Date(long1))} " +
                    "triggerAt=${DATE_FORMAT.format(Date(long2))}"
            }
        )
    }

    fun logKeyguardVisibilityChange(isVisible: Boolean) {
        buffer.log(
            TAG,
            INFO,
            { bool1 = isVisible },
            { "Keyguard visibility change, isVisible=$bool1" }
        )
    }

    fun logPendingUnscheduleTimeTick(isPending: Boolean, isTimeTickScheduled: Boolean) {
        buffer.log(
            TAG,
            INFO,
            {
                bool1 = isPending
                bool2 = isTimeTickScheduled
            },
            { "Pending unschedule time tick, isPending=$bool1, isTimeTickScheduled:$bool2" }
        )
    }

    fun logDozeStateChanged(state: DozeMachine.State) {
        buffer.log(TAG, INFO, { str1 = state.name }, { "Doze state changed to $str1" })
    }

    fun logStateChangedSent(state: DozeMachine.State) {
        buffer.log(
            TAG,
            INFO,
            { str1 = state.name },
            { "Doze state sent to all DozeMachineParts stateSent=$str1" }
        )
    }

    fun logDisplayStateDelayedByUdfps(delayedDisplayState: Int) {
        buffer.log(
            TAG,
            INFO,
            { str1 = Display.stateToString(delayedDisplayState) },
            { "Delaying display state change to: $str1 due to UDFPS activity" }
        )
    }

    fun logDisplayStateChanged(displayState: Int, afterRequest: Boolean) {
        buffer.log(
            TAG,
            INFO,
            {
                str1 = Display.stateToString(displayState)
                bool1 = afterRequest
            },
            { "Display state ${if (bool1) "changed" else "requested"} to $str1" }
        )
    }

    fun logWakeDisplay(isAwake: Boolean, @Reason reason: Int) {
        buffer.log(
            TAG,
            DEBUG,
            {
                bool1 = isAwake
                int1 = reason
            },
            { "Display wakefulness changed, isAwake=$bool1, reason=${reasonToString(int1)}" }
        )
    }

    fun logProximityResult(isNear: Boolean, millis: Long, @Reason reason: Int) {
        buffer.log(
            TAG,
            DEBUG,
            {
                bool1 = isNear
                long1 = millis
                int1 = reason
            },
            { "Proximity result reason=${reasonToString(int1)} near=$bool1 millis=$long1" }
        )
    }

    fun logPostureChanged(posture: Int, partUpdated: String) {
        buffer.log(
            TAG,
            INFO,
            {
                int1 = posture
                str1 = partUpdated
            },
            {
                "Posture changed, posture=${DevicePostureController.devicePostureToString(int1)}" +
                    " partUpdated=$str1"
            }
        )
    }

    /**
     * Log why a pulse was dropped and the current doze machine state. The state can be null if the
     * DozeMachine is the middle of transitioning between states.
     */
    fun logPulseDropped(from: String, state: DozeMachine.State?) {
        buffer.log(
            TAG,
            INFO,
            {
                str1 = from
                str2 = state?.name
            },
            { "Pulse dropped, cannot pulse from=$str1 state=$str2" }
        )
    }

    fun logSensorEventDropped(sensorEvent: Int, reason: String) {
        buffer.log(
            TAG,
            INFO,
            {
                int1 = sensorEvent
                str1 = reason
            },
            { "SensorEvent [$int1] dropped, reason=$str1" }
        )
    }

    fun logPulseEvent(pulseEvent: String, dozing: Boolean, pulseReason: String) {
        buffer.log(
            TAG,
            DEBUG,
            {
                str1 = pulseEvent
                bool1 = dozing
                str2 = pulseReason
            },
            { "Pulse-$str1 dozing=$bool1 pulseReason=$str2" }
        )
    }

    fun logPulseDropped(reason: String) {
        buffer.log(TAG, INFO, { str1 = reason }, { "Pulse dropped, why=$str1" })
    }

    fun logPulseTouchDisabledByProx(disabled: Boolean) {
        buffer.log(
            TAG,
            DEBUG,
            { bool1 = disabled },
            { "Pulse touch modified by prox, disabled=$bool1" }
        )
    }

    fun logSensorTriggered(@Reason reason: Int) {
        buffer.log(
            TAG,
            DEBUG,
            { int1 = reason },
            { "Sensor triggered, type=${reasonToString(int1)}" }
        )
    }

    fun logAlwaysOnSuppressed(state: DozeMachine.State, reason: String) {
        buffer.log(
            TAG,
            INFO,
            {
                str1 = state.name
                str2 = reason
            },
            { "Always-on state suppressed, suppressed state=$str1 reason=$str2" }
        )
    }

    fun logImmediatelyEndDoze(reason: String) {
        buffer.log(TAG, INFO, { str1 = reason }, { "Doze immediately ended due to $str1" })
    }

    fun logDozeScreenBrightness(brightness: Int, afterRequest: Boolean) {
        buffer.log(
            TAG,
            INFO,
            {
                int1 = brightness
                bool1 = afterRequest
            },
            {
                "Doze screen brightness ${if (bool1) "set" else "requested"}" +
                    " (int), brightness=$int1"
            }
        )
    }

    fun logDozeScreenBrightnessFloat(brightness: Float, afterRequest: Boolean) {
        buffer.log(
            TAG,
            INFO,
            {
                double1 = brightness.toDouble()
                bool1 = afterRequest
            },
            {
                "Doze screen brightness ${if (bool1) "set" else "requested"}" +
                    " (float), brightness=$double1"
            }
        )
    }

    fun logSetAodDimmingScrim(scrimOpacity: Long) {
        buffer.log(
            TAG,
            INFO,
            { long1 = scrimOpacity },
            { "Doze aod dimming scrim opacity set, opacity=$long1" }
        )
    }

    fun logCarModeEnded() {
        buffer.log(TAG, INFO, {}, { "Doze car mode ended" })
    }

    fun logCarModeStarted() {
        buffer.log(TAG, INFO, {}, { "Doze car mode started" })
    }

    fun logSensorRegisterAttempt(sensorInfo: String, successfulRegistration: Boolean) {
        buffer.log(
            TAG,
            INFO,
            {
                str1 = sensorInfo
                bool1 = successfulRegistration
            },
            { "Register sensor. Success=$bool1 sensor=$str1" }
        )
    }

    fun logSensorUnregisterAttempt(sensorInfo: String, successfulUnregister: Boolean) {
        buffer.log(
            TAG,
            INFO,
            {
                str1 = sensorInfo
                bool1 = successfulUnregister
            },
            { "Unregister sensor. Success=$bool1 sensor=$str1" }
        )
    }

    fun logSensorUnregisterAttempt(
        sensorInfo: String,
        successfulUnregister: Boolean,
        reason: String
    ) {
        buffer.log(
            TAG,
            INFO,
            {
                str1 = sensorInfo
                bool1 = successfulUnregister
                str2 = reason
            },
            { "Unregister sensor. reason=$str2. Success=$bool1 sensor=$str1" }
        )
    }

    fun logSkipSensorRegistration(sensor: String) {
        buffer.log(
            TAG,
            DEBUG,
            { str1 = sensor },
            { "Skipping sensor registration because its already registered. sensor=$str1" }
        )
    }

    fun logSetIgnoreTouchWhilePulsing(ignoreTouchWhilePulsing: Boolean) {
        buffer.log(
            TAG,
            DEBUG,
            { bool1 = ignoreTouchWhilePulsing },
            { "Prox changed while pulsing. setIgnoreTouchWhilePulsing=$bool1" }
        )
    }

    fun log(@CompileTimeConstant msg: String) {
        buffer.log(TAG, DEBUG, msg)
    }
}

private const val TAG = "DozeLog"

val DATE_FORMAT = SimpleDateFormat("MM-dd HH:mm:ss.S", Locale.US)
