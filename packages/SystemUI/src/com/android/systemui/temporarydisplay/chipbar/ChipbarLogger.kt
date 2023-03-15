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

package com.android.systemui.temporarydisplay.chipbar

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.plugins.log.LogBuffer
import com.android.systemui.plugins.log.LogLevel
import com.android.systemui.temporarydisplay.TemporaryViewLogger
import com.android.systemui.temporarydisplay.dagger.ChipbarLog
import javax.inject.Inject

/** A logger for the chipbar. */
@SysUISingleton
class ChipbarLogger
@Inject
constructor(
    @ChipbarLog buffer: LogBuffer,
) : TemporaryViewLogger<ChipbarInfo>(buffer, "ChipbarLog") {
    /**
     * Logs that the chipbar was updated to display in a window named [windowTitle], with [text] and
     * [endItemDesc].
     */
    fun logViewUpdate(windowTitle: String, text: String?, endItemDesc: String) {
        buffer.log(
            tag,
            LogLevel.DEBUG,
            {
                str1 = windowTitle
                str2 = text
                str3 = endItemDesc
            },
            { "Chipbar updated. window=$str1 text=$str2 endItem=$str3" }
        )
    }
}
