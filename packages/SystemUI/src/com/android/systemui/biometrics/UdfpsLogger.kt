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

package com.android.systemui.biometrics

import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.core.LogLevel.ERROR
import com.android.systemui.log.core.LogLevel.VERBOSE
import com.android.systemui.log.core.LogLevel.WARNING
import com.android.systemui.log.dagger.UdfpsLog
import com.google.errorprone.annotations.CompileTimeConstant
import javax.inject.Inject

private const val TAG = "UdfpsLogger"

/** Helper class for logging for Udfps */
class UdfpsLogger @Inject constructor(@UdfpsLog private val logBuffer: LogBuffer) {
    fun e(tag: String, @CompileTimeConstant msg: String) = log(tag, msg, ERROR)

    fun e(tag: String, @CompileTimeConstant msg: String, throwable: Throwable?) {
        logBuffer.log(tag, ERROR, {}, { msg }, exception = throwable)
    }

    fun v(tag: String, @CompileTimeConstant msg: String) = log(tag, msg, VERBOSE)

    fun w(tag: String, @CompileTimeConstant msg: String) = log(tag, msg, WARNING)

    fun log(tag: String, @CompileTimeConstant msg: String, level: LogLevel) {
        logBuffer.log(tag, level, msg)
    }

    fun requestMaxRefreshRate(request: Boolean) {
        logBuffer.log("RefreshRate", LogLevel.DEBUG, { bool1 = request }, { "Request max: $bool1" })
    }
}
