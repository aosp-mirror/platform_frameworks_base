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

package com.android.systemui.shade.carrier

import com.android.keyguard.CarrierTextManager
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import javax.inject.Inject

/** Logger for [ShadeCarrierGroupController], mostly to try and solve b/341841138. */
@SysUISingleton
class ShadeCarrierGroupControllerLogger
@Inject
constructor(@ShadeCarrierGroupControllerLog val buffer: LogBuffer) {
    /** De-structures the info object so that we don't have to generate new strings */
    fun logHandleUpdateCarrierInfo(info: CarrierTextManager.CarrierTextCallbackInfo) {
        buffer.log(
            TAG,
            LogLevel.VERBOSE,
            {
                str1 = "${info.carrierText}"
                bool1 = info.anySimReady
                bool2 = info.airplaneMode
            },
            {
                "handleUpdateCarrierInfo: " +
                    "result=(carrierText=$str1, anySimReady=$bool1, airplaneMode=$bool2)"
            },
        )
    }

    fun logInvalidArrayLengths(numCarriers: Int, numSubs: Int) {
        buffer.log(
            TAG,
            LogLevel.ERROR,
            {
                int1 = numCarriers
                int2 = numSubs
            },
            { "┗ carriers.length != subIds.length. carriers.length=$int1 subs.length=$int2" },
        )
    }

    fun logUsingNoSimView(text: CharSequence) {
        buffer.log(
            TAG,
            LogLevel.VERBOSE,
            { str1 = "$text" },
            { "┗ updating No SIM view with text=$str1" },
        )
    }

    fun logUsingSimViews() {
        buffer.log(TAG, LogLevel.VERBOSE, {}, { "┗ updating SIM views" })
    }

    private companion object {
        const val TAG = "SCGC"
    }
}
