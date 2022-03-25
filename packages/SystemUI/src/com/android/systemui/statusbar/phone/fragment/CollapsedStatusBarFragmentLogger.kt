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

package com.android.systemui.statusbar.phone.fragment

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

    /**
     * Logs a string representing the new state received by [CollapsedStatusBarFragment] and any
     * modifications that were made to the flags locally.
     *
     * @param new see [DisableFlagsLogger.getDisableFlagsString]
     * @param newAfterLocalModification see [DisableFlagsLogger.getDisableFlagsString]
     */
    fun logDisableFlagChange(
        new: DisableFlagsLogger.DisableState,
        newAfterLocalModification: DisableFlagsLogger.DisableState
    ) {
        buffer.log(
                TAG,
                LogLevel.INFO,
                {
                    int1 = new.disable1
                    int2 = new.disable2
                    long1 = newAfterLocalModification.disable1.toLong()
                    long2 = newAfterLocalModification.disable2.toLong()
                },
                {
                    disableFlagsLogger.getDisableFlagsString(
                        old = null,
                        new = DisableFlagsLogger.DisableState(int1, int2),
                        newAfterLocalModification =
                            DisableFlagsLogger.DisableState(long1.toInt(), long2.toInt())
                    )
                }
        )
    }
}

private const val TAG = "CollapsedSbFragment"