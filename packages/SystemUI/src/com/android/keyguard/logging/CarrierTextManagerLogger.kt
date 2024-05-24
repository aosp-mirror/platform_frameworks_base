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

package com.android.keyguard.logging

import androidx.annotation.IntDef
import com.android.keyguard.CarrierTextManager.CarrierTextCallbackInfo
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.dagger.CarrierTextManagerLog
import javax.inject.Inject

/** Logger adapter for [CarrierTextManager] to add detailed messages in a [LogBuffer] */
class CarrierTextManagerLogger @Inject constructor(@CarrierTextManagerLog val buffer: LogBuffer) {
    /**
     * To help disambiguate carrier text manager instances, set a location string here which will
     * propagate to [logUpdate] and [logUpdateCarrierTextForReason]
     */
    var location: String? = null

    /**
     * This method and the methods below trace the execution of CarrierTextManager.updateCarrierText
     */
    fun logUpdate(numSubs: Int) {
        buffer.log(
            TAG,
            LogLevel.VERBOSE,
            { int1 = numSubs },
            { "updateCarrierText: location=${location ?: "(unknown)"} numSubs=$int1" },
        )
    }

    fun logUpdateLoopStart(sub: Int, simState: Int, carrierName: String) {
        buffer.log(
            TAG,
            LogLevel.VERBOSE,
            {
                int1 = sub
                int2 = simState
                str1 = carrierName
            },
            { "┣ updateCarrierText: updating sub=$int1 simState=$int2 carrierName=$str1" },
        )
    }

    fun logUpdateWfcCheck() {
        buffer.log(
            TAG,
            LogLevel.VERBOSE,
            {},
            { "┣ updateCarrierText: found WFC state" },
        )
    }

    fun logUpdateFromStickyBroadcast(plmn: String?, spn: String?) {
        buffer.log(
            TAG,
            LogLevel.VERBOSE,
            {
                str1 = plmn
                str2 = spn
            },
            { "┣ updateCarrierText: getting PLMN/SPN sticky brdcst. plmn=$str1, spn=$str1" },
        )
    }

    /** De-structures the info object so that we don't have to generate new strings */
    fun logCallbackSentFromUpdate(info: CarrierTextCallbackInfo) {
        buffer.log(
            TAG,
            LogLevel.VERBOSE,
            {
                str1 = "${info.carrierText}"
                bool1 = info.anySimReady
                bool2 = info.airplaneMode
            },
            {
                "┗ updateCarrierText: " +
                    "result=(carrierText=$str1, anySimReady=$bool1, airplaneMode=$bool2)"
            },
        )
    }

    fun logSimStateChangedCallback(subId: Int, slotId: Int, simState: Int) {
        buffer.log(
            TAG,
            LogLevel.VERBOSE,
            {
                // subId is always a very small int, and we've run out of integers for log buffer
                long1 = subId.toLong()
                int1 = slotId
                int2 = simState
            },
            { "onSimStateChangedCallback: subId=$long1 slotId=$int1 simState=$int2" }
        )
    }

    /**
     * Used to log the starting point for _why_ the carrier text is updating. In order to keep us
     * from holding on to too many objects, we'll just use simple ints for reasons here
     */
    fun logUpdateCarrierTextForReason(@CarrierTextRefreshReason reason: Int) {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            { int1 = reason },
            {
                "refreshing carrier info for reason: ${reason.reasonMessage()}" +
                    " location=${location ?: "(unknown)"}"
            }
        )
    }

    companion object {
        const val REASON_REFRESH_CARRIER_INFO = 1
        const val REASON_ON_TELEPHONY_CAPABLE = 2
        const val REASON_SIM_ERROR_STATE_CHANGED = 3
        const val REASON_ACTIVE_DATA_SUB_CHANGED = 4

        @Retention(AnnotationRetention.SOURCE)
        @IntDef(
            value =
                [
                    REASON_REFRESH_CARRIER_INFO,
                    REASON_ON_TELEPHONY_CAPABLE,
                    REASON_SIM_ERROR_STATE_CHANGED,
                    REASON_ACTIVE_DATA_SUB_CHANGED,
                ]
        )
        annotation class CarrierTextRefreshReason

        private fun @receiver:CarrierTextRefreshReason Int.reasonMessage() =
            when (this) {
                REASON_REFRESH_CARRIER_INFO -> "REFRESH_CARRIER_INFO"
                REASON_ON_TELEPHONY_CAPABLE -> "ON_TELEPHONY_CAPABLE"
                REASON_SIM_ERROR_STATE_CHANGED -> "SIM_ERROR_STATE_CHANGED"
                REASON_ACTIVE_DATA_SUB_CHANGED -> "ACTIVE_DATA_SUB_CHANGED"
                else -> "unknown"
            }
    }
}

private const val TAG = "CarrierTextManagerLog"
