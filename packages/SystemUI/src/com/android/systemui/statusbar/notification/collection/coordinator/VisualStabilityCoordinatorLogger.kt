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

package com.android.systemui.statusbar.notification.collection.coordinator

import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.dagger.VisualStabilityLog
import javax.inject.Inject

private const val TAG = "VisualStability"

class VisualStabilityCoordinatorLogger
@Inject
constructor(@VisualStabilityLog private val buffer: LogBuffer) {
    fun logAllowancesChanged(
        wasRunAllowed: Boolean,
        isRunAllowed: Boolean,
        wasReorderingAllowed: Boolean,
        isReorderingAllowed: Boolean,
        field: String,
        value: Boolean
    ) {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                bool1 = wasRunAllowed
                bool2 = isRunAllowed
                bool3 = wasReorderingAllowed
                bool4 = isReorderingAllowed
                str1 = field
                str2 = value.toString()
            },
            {
                "stability allowances changed:" +
                    " pipelineRunAllowed $bool1->$bool2" +
                    " reorderingAllowed $bool3->$bool4" +
                    " when setting $str1=$str2"
            }
        )
    }
}
