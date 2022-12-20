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

package com.android.systemui.shade

import android.view.WindowManager
import com.android.systemui.log.dagger.ShadeWindowLog
import com.android.systemui.plugins.log.ConstantStringsLogger
import com.android.systemui.plugins.log.ConstantStringsLoggerImpl
import com.android.systemui.plugins.log.LogBuffer
import com.android.systemui.plugins.log.LogLevel
import com.android.systemui.plugins.log.LogLevel.DEBUG
import com.android.systemui.plugins.log.LogMessage
import javax.inject.Inject

private const val TAG = "systemui.shadewindow"

class ShadeWindowLogger @Inject constructor(@ShadeWindowLog private val buffer: LogBuffer) :
    ConstantStringsLogger by ConstantStringsLoggerImpl(buffer, TAG) {

    fun logApplyingWindowLayoutParams(lp: WindowManager.LayoutParams) {
        log(DEBUG, { str1 = lp.toString() }, { "Applying new window layout params: $str1" })
    }

    fun logNewState(state: Any) {
        log(DEBUG, { str1 = state.toString() }, { "Applying new state: $str1" })
    }

    private inline fun log(
        logLevel: LogLevel,
        initializer: LogMessage.() -> Unit,
        noinline printer: LogMessage.() -> String
    ) {
        buffer.log(TAG, logLevel, initializer, printer)
    }

    fun logApplyVisibility(visible: Boolean) {
        log(DEBUG, { bool1 = visible }, { "Updating visibility, should be visible : $bool1" })
    }

    fun logShadeVisibleAndFocusable(visible: Boolean) {
        log(
            DEBUG,
            { bool1 = visible },
            { "Updating shade, should be visible and focusable: $bool1" }
        )
    }

    fun logShadeFocusable(focusable: Boolean) {
        log(DEBUG, { bool1 = focusable }, { "Updating shade, should be focusable : $bool1" })
    }
}
