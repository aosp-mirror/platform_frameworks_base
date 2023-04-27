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
import com.android.systemui.plugins.log.LogLevel.DEBUG
import javax.inject.Inject

/** Helper class for logging for [com.android.systemui.biometrics.FaceHelpMessageDeferral] */
@SysUISingleton
class FaceMessageDeferralLogger
@Inject
constructor(@BiometricLog private val logBuffer: LogBuffer) :
    BiometricMessageDeferralLogger(logBuffer, "FaceMessageDeferralLogger")

open class BiometricMessageDeferralLogger(
    private val logBuffer: LogBuffer,
    private val tag: String
) {
    fun reset() {
        logBuffer.log(tag, DEBUG, "reset")
    }

    fun logUpdateMessage(acquiredInfo: Int, helpString: String) {
        logBuffer.log(
            tag,
            DEBUG,
            {
                int1 = acquiredInfo
                str1 = helpString
            },
            { "updateMessage acquiredInfo=$int1 helpString=$str1" }
        )
    }

    fun logFrameProcessed(
        acquiredInfo: Int,
        totalFrames: Int,
        mostFrequentAcquiredInfoToDefer: String? // may not meet the threshold
    ) {
        logBuffer.log(
            tag,
            DEBUG,
            {
                int1 = acquiredInfo
                int2 = totalFrames
                str1 = mostFrequentAcquiredInfoToDefer
            },
            {
                "frameProcessed acquiredInfo=$int1 totalFrames=$int2 " +
                    "messageToShowOnTimeout=$str1"
            }
        )
    }
}
