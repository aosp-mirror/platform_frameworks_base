/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.toast

import com.android.systemui.log.dagger.ToastLog
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.core.LogLevel.DEBUG
import com.android.systemui.log.core.LogMessage
import javax.inject.Inject

private const val TAG = "ToastLog"

class ToastLogger @Inject constructor(
    @ToastLog private val buffer: LogBuffer
) {

    fun logOnShowToast(uid: Int, packageName: String, text: String, token: String) {
        log(DEBUG, {
            int1 = uid
            str1 = packageName
            str2 = text
            str3 = token
        }, {
            "[$str3] Show toast for ($str1, $int1). msg=\'$str2\'"
        })
    }

    fun logOnHideToast(packageName: String, token: String) {
        log(DEBUG, {
            str1 = packageName
            str2 = token
        }, {
            "[$str2] Hide toast for [$str1]"
        })
    }

    fun logOrientationChange(text: String, isPortrait: Boolean) {
        log(DEBUG, {
            str1 = text
            bool1 = isPortrait
        }, {
            "Orientation change for toast. msg=\'$str1\' isPortrait=$bool1"
        })
    }

    fun logOnSkipToastForInvalidDisplay(packageName: String, token: String, displayId: Int) {
        log(DEBUG, {
            str1 = packageName
            str2 = token
            int1 = displayId
        }, {
            "[$str2] Skip toast for [$str1] scheduled on unavailable display #$int1"
        })
    }

    private inline fun log(
        logLevel: LogLevel,
        initializer: LogMessage.() -> Unit,
        noinline printer: LogMessage.() -> String
    ) {
        buffer.log(TAG, logLevel, initializer, printer)
    }
}