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

import com.android.systemui.biometrics.AuthRippleController
import com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController
import com.android.systemui.log.dagger.KeyguardLog
import com.android.systemui.plugins.log.LogBuffer
import com.android.systemui.plugins.log.LogLevel
import com.android.systemui.statusbar.KeyguardIndicationController
import com.google.errorprone.annotations.CompileTimeConstant
import javax.inject.Inject

private const val BIO_TAG = "KeyguardLog"

/**
 * Generic logger for keyguard that's wrapping [LogBuffer]. This class should be used for adding
 * temporary logs or logs for smaller classes when creating whole new [LogBuffer] wrapper might be
 * an overkill.
 */
class KeyguardLogger
@Inject
constructor(
    @KeyguardLog val buffer: LogBuffer,
) {
    @JvmOverloads
    fun log(
        tag: String,
        level: LogLevel,
        @CompileTimeConstant msg: String,
        ex: Throwable? = null,
    ) = buffer.log(tag, level, msg, ex)

    fun log(
        tag: String,
        level: LogLevel,
        @CompileTimeConstant msg: String,
        arg: Any,
    ) {
        buffer.log(
            tag,
            level,
            {
                str1 = msg
                str2 = arg.toString()
            },
            { "$str1: $str2" }
        )
    }

    @JvmOverloads
    fun logBiometricMessage(
        @CompileTimeConstant context: String,
        msgId: Int? = null,
        msg: String? = null
    ) {
        buffer.log(
            BIO_TAG,
            LogLevel.DEBUG,
            {
                str1 = context
                str2 = "$msgId"
                str3 = msg
            },
            { "$str1 msgId: $str2 msg: $str3" }
        )
    }

    fun logUpdateDeviceEntryIndication(
        animate: Boolean,
        visible: Boolean,
        dozing: Boolean,
    ) {
        buffer.log(
            KeyguardIndicationController.TAG,
            LogLevel.DEBUG,
            {
                bool1 = animate
                bool2 = visible
                bool3 = dozing
            },
            { "updateDeviceEntryIndication animate:$bool1 visible:$bool2 dozing $bool3" }
        )
    }

    fun logUpdateBatteryIndication(
        powerIndication: String,
        pluggedIn: Boolean,
    ) {
        buffer.log(
            KeyguardIndicationController.TAG,
            LogLevel.DEBUG,
            {
                str1 = powerIndication
                bool1 = pluggedIn
            },
            { "updateBatteryIndication powerIndication:$str1 pluggedIn:$bool1" }
        )
    }

    fun logKeyguardSwitchIndication(
        type: Int,
        message: String?,
    ) {
        buffer.log(
            KeyguardIndicationController.TAG,
            LogLevel.DEBUG,
            {
                int1 = type
                str1 = message
            },
            { "keyguardSwitchIndication ${getKeyguardSwitchIndicationNonSensitiveLog(int1, str1)}" }
        )
    }

    fun logRefreshBatteryInfo(
        isChargingOrFull: Boolean,
        powerPluggedIn: Boolean,
        batteryLevel: Int,
        batteryOverheated: Boolean
    ) {
        buffer.log(
            KeyguardIndicationController.TAG,
            LogLevel.DEBUG,
            {
                bool1 = isChargingOrFull
                bool2 = powerPluggedIn
                bool3 = batteryOverheated
                int1 = batteryLevel
            },
            {
                "refreshBatteryInfo isChargingOrFull:$bool1 powerPluggedIn:$bool2" +
                    " batteryOverheated:$bool3 batteryLevel:$int1"
            }
        )
    }

    fun getKeyguardSwitchIndicationNonSensitiveLog(type: Int, message: String?): String {
        // only show the battery string. other strings may contain sensitive info
        return if (type == KeyguardIndicationRotateTextViewController.INDICATION_TYPE_BATTERY) {
            "type=${KeyguardIndicationRotateTextViewController.indicationTypeToString(type)}" +
                " message=$message"
        } else {
            "type=${KeyguardIndicationRotateTextViewController.indicationTypeToString(type)}"
        }
    }

    fun notShowingUnlockRipple(keyguardNotShowing: Boolean, unlockNotAllowed: Boolean) {
        buffer.log(
            AuthRippleController.TAG,
            LogLevel.DEBUG,
            {
                bool1 = keyguardNotShowing
                bool2 = unlockNotAllowed
            },
            { "Not showing unlock ripple: keyguardNotShowing: $bool1, unlockNotAllowed: $bool2" }
        )
    }

    fun showingUnlockRippleAt(x: Int, y: Int, context: String) {
        buffer.log(
            AuthRippleController.TAG,
            LogLevel.DEBUG,
            {
                int1 = x
                int2 = y
                str1 = context
            },
            { "Showing unlock ripple with center (x, y): ($int1, $int2), context: $str1" }
        )
    }
}
