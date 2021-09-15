/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.statusbar.phone

import com.android.systemui.log.LogBuffer
import com.android.systemui.log.LogLevel
import com.android.systemui.log.dagger.CollapsedSbFragmentLog
import com.android.systemui.statusbar.DisableFlagsLogger
import javax.inject.Inject

/** Used by [CollapsedStatusBarFragment] to log messages to a [LogBuffer]. */
class CollapsedStatusBarFragmentLogger @Inject constructor(
        @CollapsedSbFragmentLog private val buffer: LogBuffer,
        private val disableFlagsLogger: DisableFlagsLogger,
) {

    /** Logs a string representing the old and new disable flag states to [buffer]. */
    fun logDisableFlagChange(
            oldState: DisableFlagsLogger.DisableState,
            newState: DisableFlagsLogger.DisableState) {
        buffer.log(
                TAG,
                LogLevel.INFO,
                {
                    int1 = oldState.disable1
                    int2 = oldState.disable2
                    long1 = newState.disable1.toLong()
                    long2 = newState.disable2.toLong()
                },
                {
                    disableFlagsLogger.getDisableFlagsString(
                            DisableFlagsLogger.DisableState(int1, int2),
                            DisableFlagsLogger.DisableState(long1.toInt(), long2.toInt())
                    )
                }
        )
    }
}

private const val TAG = "CollapsedSbFragment"