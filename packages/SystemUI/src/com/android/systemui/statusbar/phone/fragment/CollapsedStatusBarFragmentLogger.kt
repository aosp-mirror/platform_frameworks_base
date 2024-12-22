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
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.dagger.CollapsedSbFragmentLog
import com.android.systemui.statusbar.disableflags.DisableFlagsLogger
import javax.inject.Inject

/** Used by [CollapsedStatusBarFragment] to log messages to a [LogBuffer]. */
class CollapsedStatusBarFragmentLogger
@Inject
constructor(
    @CollapsedSbFragmentLog private val buffer: LogBuffer,
    private val disableFlagsLogger: DisableFlagsLogger,
) {

    /**
     * Logs a string representing the new state received by [CollapsedStatusBarFragment] and any
     * modifications that were made to the flags locally.
     *
     * @param new see [DisableFlagsLogger.getDisableFlagsString]
     */
    fun logDisableFlagChange(
        new: DisableFlagsLogger.DisableState,
    ) {
        buffer.log(
            TAG,
            LogLevel.INFO,
            {
                int1 = new.disable1
                int2 = new.disable2
            },
            {
                disableFlagsLogger.getDisableFlagsString(
                    DisableFlagsLogger.DisableState(int1, int2),
                )
            }
        )
    }

    fun logVisibilityModel(model: StatusBarVisibilityModel) {
        buffer.log(
            TAG,
            LogLevel.INFO,
            {
                bool1 = model.showClock
                bool2 = model.showNotificationIcons
                bool3 = model.showPrimaryOngoingActivityChip
                int1 = if (model.showSecondaryOngoingActivityChip) 1 else 0
                bool4 = model.showSystemInfo
            },
            {
                "New visibilities calculated internally. " +
                    "showClock=$bool1 " +
                    "showNotificationIcons=$bool2 " +
                    "showPrimaryOngoingActivityChip=$bool3 " +
                    "showSecondaryOngoingActivityChip=${if (int1 == 1) "true" else "false"}" +
                    "showSystemInfo=$bool4"
            }
        )
    }
}

private const val TAG = "CollapsedSbFragment"
