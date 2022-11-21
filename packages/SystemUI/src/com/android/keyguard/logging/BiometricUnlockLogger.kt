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

package com.android.keyguard.logging

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.log.dagger.BiometricLog
import com.android.systemui.plugins.log.LogBuffer
import com.android.systemui.plugins.log.LogLevel
import com.android.systemui.plugins.log.LogLevel.DEBUG
import com.android.systemui.plugins.log.LogLevel.INFO
import com.android.systemui.statusbar.phone.BiometricUnlockController.MODE_DISMISS_BOUNCER
import com.android.systemui.statusbar.phone.BiometricUnlockController.MODE_NONE
import com.android.systemui.statusbar.phone.BiometricUnlockController.MODE_ONLY_WAKE
import com.android.systemui.statusbar.phone.BiometricUnlockController.MODE_SHOW_BOUNCER
import com.android.systemui.statusbar.phone.BiometricUnlockController.MODE_UNLOCK_COLLAPSING
import com.android.systemui.statusbar.phone.BiometricUnlockController.MODE_WAKE_AND_UNLOCK
import com.android.systemui.statusbar.phone.BiometricUnlockController.MODE_WAKE_AND_UNLOCK_FROM_DREAM
import com.android.systemui.statusbar.phone.BiometricUnlockController.MODE_WAKE_AND_UNLOCK_PULSING
import com.google.errorprone.annotations.CompileTimeConstant
import javax.inject.Inject

private const val TAG = "BiometricUnlockLogger"

/** Helper class for logging for [com.android.systemui.statusbar.phone.BiometricUnlockController] */
@SysUISingleton
class BiometricUnlockLogger @Inject constructor(@BiometricLog private val logBuffer: LogBuffer) {
    fun i(@CompileTimeConstant msg: String) = log(msg, INFO)
    fun d(@CompileTimeConstant msg: String) = log(msg, DEBUG)
    fun log(@CompileTimeConstant msg: String, level: LogLevel) = logBuffer.log(TAG, level, msg)

    fun logStartWakeAndUnlock(mode: Int) {
        logBuffer.log(
            TAG,
            DEBUG,
            { int1 = mode },
            { "startWakeAndUnlock(${wakeAndUnlockModeToString(int1)})" }
        )
    }

    fun logUdfpsAttemptThresholdMet(consecutiveFailedAttempts: Int) {
        logBuffer.log(
            TAG,
            DEBUG,
            { int1 = consecutiveFailedAttempts },
            { "udfpsAttemptThresholdMet consecutiveFailedAttempts=$int1" }
        )
    }

    fun logCalculateModeForFingerprintUnlockingAllowed(
        deviceInteractive: Boolean,
        keyguardShowing: Boolean,
        deviceDreaming: Boolean
    ) {
        logBuffer.log(
            TAG,
            DEBUG,
            {
                bool1 = deviceInteractive
                bool2 = keyguardShowing
                bool3 = deviceDreaming
            },
            {
                "calculateModeForFingerprint unlockingAllowed=true" +
                    " deviceInteractive=$bool1 isKeyguardShowing=$bool2" +
                    " deviceDreaming=$bool3"
            }
        )
    }

    fun logCalculateModeForFingerprintUnlockingNotAllowed(
        strongBiometric: Boolean,
        strongAuthFlags: Int,
        nonStrongBiometricAllowed: Boolean,
        deviceInteractive: Boolean,
        keyguardShowing: Boolean
    ) {
        logBuffer.log(
            TAG,
            DEBUG,
            {
                int1 = strongAuthFlags
                bool1 = strongBiometric
                bool2 = nonStrongBiometricAllowed
                bool3 = deviceInteractive
                bool4 = keyguardShowing
            },
            {
                "calculateModeForFingerprint unlockingAllowed=false" +
                    " strongBiometric=$bool1 strongAuthFlags=$int1" +
                    " nonStrongBiometricAllowed=$bool2" +
                    " deviceInteractive=$bool3 isKeyguardShowing=$bool4"
            }
        )
    }

    fun logCalculateModeForPassiveAuthUnlockingAllowed(
        deviceInteractive: Boolean,
        keyguardShowing: Boolean,
        deviceDreaming: Boolean,
        bypass: Boolean
    ) {
        logBuffer.log(
            TAG,
            DEBUG,
            {
                bool1 = deviceInteractive
                bool2 = keyguardShowing
                bool3 = deviceDreaming
                bool4 = bypass
            },
            {
                "calculateModeForPassiveAuth unlockingAllowed=true" +
                    " deviceInteractive=$bool1 isKeyguardShowing=$bool2" +
                    " deviceDreaming=$bool3 bypass=$bool4"
            }
        )
    }

    fun logCalculateModeForPassiveAuthUnlockingNotAllowed(
        strongBiometric: Boolean,
        strongAuthFlags: Int,
        nonStrongBiometricAllowed: Boolean,
        deviceInteractive: Boolean,
        keyguardShowing: Boolean,
        bypass: Boolean
    ) {
        logBuffer.log(
            TAG,
            DEBUG,
            {
                int1 = if (strongBiometric) 1 else 0
                int2 = strongAuthFlags
                bool1 = nonStrongBiometricAllowed
                bool2 = deviceInteractive
                bool3 = keyguardShowing
                bool4 = bypass
            },
            {
                "calculateModeForPassiveAuth unlockingAllowed=false" +
                    " strongBiometric=${int1 == 1}" +
                    " strongAuthFlags=$int2 nonStrongBiometricAllowed=$bool1" +
                    " deviceInteractive=$bool2 isKeyguardShowing=$bool3 bypass=$bool4"
            }
        )
    }
}

private fun wakeAndUnlockModeToString(mode: Int): String {
    return when (mode) {
        MODE_NONE -> "MODE_NONE"
        MODE_WAKE_AND_UNLOCK -> "MODE_WAKE_AND_UNLOCK"
        MODE_WAKE_AND_UNLOCK_PULSING -> "MODE_WAKE_AND_UNLOCK_PULSING"
        MODE_SHOW_BOUNCER -> "MODE_SHOW_BOUNCER"
        MODE_ONLY_WAKE -> "MODE_ONLY_WAKE"
        MODE_UNLOCK_COLLAPSING -> "MODE_UNLOCK_COLLAPSING"
        MODE_WAKE_AND_UNLOCK_FROM_DREAM -> "MODE_WAKE_AND_UNLOCK_FROM_DREAM"
        MODE_DISMISS_BOUNCER -> "MODE_DISMISS_BOUNCER"
        else -> "UNKNOWN{$mode}"
    }
}
