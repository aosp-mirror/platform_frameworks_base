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

import com.android.systemui.log.dagger.KeyguardLog
import com.android.systemui.plugins.log.LogBuffer
import com.android.systemui.plugins.log.LogLevel
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
}
