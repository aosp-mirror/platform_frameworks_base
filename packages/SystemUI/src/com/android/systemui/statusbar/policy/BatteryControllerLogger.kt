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

package com.android.systemui.statusbar.policy

import android.content.Intent
import android.os.BatteryManager.EXTRA_LEVEL
import android.os.BatteryManager.EXTRA_SCALE
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.statusbar.policy.dagger.BatteryControllerLog
import javax.inject.Inject

/** Detailed, [LogBuffer]-backed logs for [BatteryControllerImpl] */
@SysUISingleton
class BatteryControllerLogger
@Inject
constructor(@BatteryControllerLog private val logBuffer: LogBuffer) {
    fun logBatteryControllerInstance(controller: BatteryController) {
        logBuffer.log(
            TAG,
            LogLevel.DEBUG,
            { int1 = System.identityHashCode(controller) },
            { "BatteryController CREATE (${Integer.toHexString(int1)})" }
        )
    }

    fun logBatteryControllerInit(controller: BatteryController, hasReceivedBattery: Boolean) {
        logBuffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                int1 = System.identityHashCode(controller)
                bool1 = hasReceivedBattery
            },
            { "BatteryController INIT (${Integer.toHexString(int1)}) hasReceivedBattery=$bool1" }
        )
    }

    fun logIntentReceived(action: String) {
        logBuffer.log(TAG, LogLevel.DEBUG, { str1 = action }, { "Received intent $str1" })
    }

    fun logBatteryChangedIntent(intent: Intent) {
        logBuffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                int1 = intent.getIntExtra(EXTRA_LEVEL, DEFAULT)
                int2 = intent.getIntExtra(EXTRA_SCALE, DEFAULT)
            },
            { "Processing BATTERY_CHANGED intent. level=${int1.report()} scale=${int2.report()}" }
        )
    }

    fun logBatteryChangedSkipBecauseTest() {
        logBuffer.log(
            TAG,
            LogLevel.DEBUG,
            {},
            { "Detected test intent. Will not execute battery level callbacks." }
        )
    }

    fun logEnterTestMode() {
        logBuffer.log(
            TAG,
            LogLevel.DEBUG,
            {},
            { "Entering test mode for BATTERY_LEVEL_TEST intent" }
        )
    }

    fun logExitTestMode() {
        logBuffer.log(TAG, LogLevel.DEBUG, {}, { "Exiting test mode" })
    }

    fun logBatteryLevelChangedCallback(level: Int, plugged: Boolean, charging: Boolean) {
        logBuffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                int1 = level
                bool1 = plugged
                bool2 = charging
            },
            {
                "Sending onBatteryLevelChanged callbacks " +
                    "with level=$int1, plugged=$bool1, charging=$bool2"
            }
        )
    }

    private fun Int.report(): String =
        if (this == DEFAULT) {
            "(missing)"
        } else {
            toString()
        }

    companion object {
        const val TAG: String = "BatteryControllerLog"
    }
}

// Use a token value so we can determine if we got the default
private const val DEFAULT = -11
