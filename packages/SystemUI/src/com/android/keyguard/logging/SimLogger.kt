/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.content.Intent
import android.telephony.ServiceState
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX
import android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID
import android.telephony.TelephonyManager
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.core.LogLevel.DEBUG
import com.android.systemui.log.core.LogLevel.ERROR
import com.android.systemui.log.core.LogLevel.INFO
import com.android.systemui.log.core.LogLevel.VERBOSE
import com.android.systemui.log.core.LogLevel.WARNING
import com.android.systemui.log.dagger.SimLog
import com.google.errorprone.annotations.CompileTimeConstant
import javax.inject.Inject

private const val TAG = "SimLog"

/** Helper class for logging for SIM events */
class SimLogger @Inject constructor(@SimLog private val logBuffer: LogBuffer) {
    fun d(@CompileTimeConstant msg: String) = log(msg, DEBUG)

    fun e(@CompileTimeConstant msg: String) = log(msg, ERROR)

    fun v(@CompileTimeConstant msg: String) = log(msg, VERBOSE)

    fun w(@CompileTimeConstant msg: String) = log(msg, WARNING)

    fun log(@CompileTimeConstant msg: String, level: LogLevel) = logBuffer.log(TAG, level, msg)

    fun logInvalidSubId(subId: Int, slotId: Int) {
        logBuffer.log(
            TAG,
            INFO,
            {
                int1 = subId
                int2 = slotId
            },
            { "Previously active subId: $int1, slotId: $int2 is now invalid, will remove" },
        )
    }

    fun logServiceStateChange(subId: Int, serviceState: ServiceState?) {
        logBuffer.log(
            TAG,
            DEBUG,
            {
                int1 = subId
                str1 = "$serviceState"
            },
            { "handleServiceStateChange(subId=$int1, serviceState=$str1)" },
        )
    }

    fun logServiceStateIntent(action: String?, serviceState: ServiceState?, subId: Int) {
        logBuffer.log(
            TAG,
            VERBOSE,
            {
                str1 = action
                str2 = "$serviceState"
                int1 = subId
            },
            { "action $str1 serviceState=$str2 subId=$int1" },
        )
    }

    fun logServiceProvidersUpdated(intent: Intent) {
        logBuffer.log(
            TAG,
            VERBOSE,
            {
                int1 = intent.getIntExtra(EXTRA_SUBSCRIPTION_INDEX, INVALID_SUBSCRIPTION_ID)
                str1 = intent.getStringExtra(TelephonyManager.EXTRA_SPN)
                str2 = intent.getStringExtra(TelephonyManager.EXTRA_PLMN)
            },
            { "action SERVICE_PROVIDERS_UPDATED subId=$int1 spn=$str1 plmn=$str2" },
        )
    }

    fun logSimState(subId: Int, slotId: Int, state: String) {
        logBuffer.log(
            TAG,
            DEBUG,
            {
                int1 = subId
                int2 = slotId
                str1 = state
            },
            { "handleSimStateChange(subId=$int1, slotId=$int2, state=$str1)" },
        )
    }

    fun logSimStateFromIntent(action: String?, extraSimState: String?, slotId: Int, subId: Int) {
        logBuffer.log(
            TAG,
            VERBOSE,
            {
                str1 = action
                str2 = extraSimState
                int1 = slotId
                int2 = subId
            },
            { "action $str1 state: $str2 slotId: $int1 subid: $int2" },
        )
    }

    fun logSimUnlocked(subId: Int) {
        logBuffer.log(TAG, VERBOSE, { int1 = subId }, { "reportSimUnlocked(subId=$int1)" })
    }

    fun logSubInfo(subInfo: SubscriptionInfo?) {
        logBuffer.log(TAG, DEBUG, { str1 = "$subInfo" }, { "SubInfo:$str1" })
    }
}
