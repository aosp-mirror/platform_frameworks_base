/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.statusbar.pipeline.mobile.ui

import android.view.View
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.plugins.log.LogBuffer
import com.android.systemui.plugins.log.LogLevel
import com.android.systemui.statusbar.pipeline.dagger.MobileViewLog
import javax.inject.Inject

/** Logs for changes with the new mobile views. */
@SysUISingleton
class MobileViewLogger @Inject constructor(
    @MobileViewLog private val buffer: LogBuffer,
) {

    fun logUiAdapterSubIdsUpdated(subs: List<Int>) {
        buffer.log(
            TAG,
            LogLevel.INFO,
            { str1 = subs.toString() },
            { "Sub IDs in MobileUiAdapter updated internally: $str1" },
        )
    }

    fun logUiAdapterSubIdsSentToIconController(subs: List<Int>) {
        buffer.log(
            TAG,
            LogLevel.INFO,
            { str1 = subs.toString() },
            { "Sub IDs in MobileUiAdapter being sent to icon controller: $str1" },
        )
    }
}

private const val TAG = "MobileViewLogger"
