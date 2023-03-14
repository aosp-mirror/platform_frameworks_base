/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.android.systemui.shade

import com.android.systemui.log.dagger.ShadeHeightLog
import com.android.systemui.plugins.log.LogBuffer
import com.android.systemui.plugins.log.LogLevel.DEBUG
import java.text.SimpleDateFormat
import javax.inject.Inject

private const val TAG = "ShadeHeightLogger"

/**
 * Log the call stack for [NotificationPanelViewController] setExpandedHeightInternal.
 *
 * Tracking bug: b/261593829
 */
class ShadeHeightLogger
@Inject constructor(
    @ShadeHeightLog private val buffer: LogBuffer,
) {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS")

    fun logFunctionCall(functionName: String) {
        buffer.log(TAG, DEBUG, {
            str1 = functionName
        }, {
            "$str1"
        })
    }

    fun logSetExpandedHeightInternal(h: Float, time: Long) {
        buffer.log(TAG, DEBUG, {
            double1 = h.toDouble()
            long1 = time
        }, {
            "setExpandedHeightInternal=$double1 time=${dateFormat.format(long1)}"
        })
    }
}