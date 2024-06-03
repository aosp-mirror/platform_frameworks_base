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

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.core.LogLevel.DEBUG
import com.android.systemui.log.core.LogLevel.INFO
import com.android.systemui.log.dagger.DeviceEntryIconLog
import com.google.errorprone.annotations.CompileTimeConstant
import javax.inject.Inject

private const val GENERIC_TAG = "DeviceEntryIconLogger"

/** Helper class for logging for the DeviceEntryIcon */
@SysUISingleton
class DeviceEntryIconLogger
@Inject
constructor(@DeviceEntryIconLog private val logBuffer: LogBuffer) {
    fun i(@CompileTimeConstant msg: String) = log(msg, INFO)
    fun d(@CompileTimeConstant msg: String) = log(msg, DEBUG)
    fun log(@CompileTimeConstant msg: String, level: LogLevel) =
        logBuffer.log(GENERIC_TAG, level, msg)

    fun logDeviceEntryUdfpsTouchOverlayShouldHandleTouches(
        shouldHandleTouches: Boolean,
        canTouchDeviceEntryViewAlpha: Boolean,
        alternateBouncerVisible: Boolean,
        hideAffordancesRequest: Boolean,
    ) {
        logBuffer.log(
            "DeviceEntryUdfpsTouchOverlay",
            DEBUG,
            {
                bool1 = canTouchDeviceEntryViewAlpha
                bool2 = alternateBouncerVisible
                bool3 = hideAffordancesRequest
                bool4 = shouldHandleTouches
            },
            {
                "shouldHandleTouches=$bool4 canTouchDeviceEntryViewAlpha=$bool1 " +
                    "alternateBouncerVisible=$bool2 hideAffordancesRequest=$bool3"
            }
        )
    }
}
