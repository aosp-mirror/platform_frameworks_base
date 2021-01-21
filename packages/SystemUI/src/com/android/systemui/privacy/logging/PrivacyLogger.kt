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

package com.android.systemui.privacy.logging

import com.android.systemui.log.LogBuffer
import com.android.systemui.log.LogLevel
import com.android.systemui.log.LogMessage
import com.android.systemui.log.dagger.PrivacyLog
import javax.inject.Inject

private const val TAG = "PrivacyLog"

class PrivacyLogger @Inject constructor(
    @PrivacyLog private val buffer: LogBuffer
) {

    fun logUpdatedItemFromAppOps(code: Int, uid: Int, packageName: String, active: Boolean) {
        log(LogLevel.INFO, {
            int1 = code
            int2 = uid
            str1 = packageName
            bool1 = active
        }, {
            "App Op: $int1 for $str1($int2), active=$bool1"
        })
    }

    fun logUpdatedPrivacyItemsList(listAsString: String) {
        log(LogLevel.INFO, {
            str1 = listAsString
        }, {
            "Updated list: $str1"
        })
    }

    fun startIndicatorsHold(time: Long) {
        log(LogLevel.DEBUG, {
            int1 = time.toInt() / 1000
        }, {
            "Starting privacy indicators hold for $int1 seconds"
        })
    }

    fun cancelIndicatorsHold() {
        log(LogLevel.VERBOSE, {}, {
            "Cancel privacy indicators hold"
        })
    }

    fun finishIndicatorsHold() {
        log(LogLevel.DEBUG, {}, {
            "Finish privacy indicators hold"
        })
    }

    fun logCurrentProfilesChanged(profiles: List<Int>) {
        log(LogLevel.INFO, {
            str1 = profiles.toString()
        }, {
            "Profiles changed: $str1"
        })
    }

    fun logChipVisible(visible: Boolean) {
        log(LogLevel.INFO, {
            bool1 = visible
        }, {
            "Chip visible: $bool1"
        })
    }

    fun logStatusBarIconsVisible(
        showCamera: Boolean,
        showMichrophone: Boolean,
        showLocation: Boolean
    ) {
        log(LogLevel.INFO, {
            bool1 = showCamera
            bool2 = showMichrophone
            bool3 = showLocation
        }, {
            "Status bar icons visible: camera=$bool1, microphone=$bool2, location=$bool3"
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