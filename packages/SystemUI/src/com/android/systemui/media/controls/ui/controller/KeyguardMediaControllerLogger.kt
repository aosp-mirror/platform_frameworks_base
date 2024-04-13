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

package com.android.systemui.media.controls.ui.controller

import android.view.ViewGroup
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel.DEBUG
import com.android.systemui.log.dagger.KeyguardMediaControllerLog
import com.android.systemui.statusbar.StatusBarState
import javax.inject.Inject

/** Logger class for [KeyguardMediaController]. */
open class KeyguardMediaControllerLogger
@Inject
constructor(@KeyguardMediaControllerLog private val logBuffer: LogBuffer) {

    fun logRefreshMediaPosition(
        reason: String,
        visible: Boolean,
        useSplitShade: Boolean,
        currentState: Int,
        keyguardOrUserSwitcher: Boolean,
        mediaHostVisible: Boolean,
        bypassNotEnabled: Boolean,
        currentAllowMediaPlayerOnLockScreen: Boolean,
        shouldBeVisibleForSplitShade: Boolean,
    ) {
        logBuffer.log(
            TAG,
            DEBUG,
            {
                str1 = reason
                bool1 = visible
                bool2 = useSplitShade
                int1 = currentState
                bool3 = keyguardOrUserSwitcher
                bool4 = mediaHostVisible
                int2 = if (bypassNotEnabled) 1 else 0
                str2 = currentAllowMediaPlayerOnLockScreen.toString()
                str3 = shouldBeVisibleForSplitShade.toString()
            },
            {
                "refreshMediaPosition(reason=$str1, " +
                    "currentState=${StatusBarState.toString(int1)}, " +
                    "visible=$bool1, useSplitShade=$bool2, " +
                    "keyguardOrUserSwitcher=$bool3, " +
                    "mediaHostVisible=$bool4, " +
                    "bypassNotEnabled=${int2 == 1}, " +
                    "currentAllowMediaPlayerOnLockScreen=$str2, " +
                    "shouldBeVisibleForSplitShade=$str3)"
            }
        )
    }

    fun logActiveMediaContainer(reason: String, activeContainer: ViewGroup?) {
        logBuffer.log(
            TAG,
            DEBUG,
            {
                str1 = reason
                str2 = activeContainer.toString()
            },
            { "activeMediaContainerVisibility(reason=$str1, activeContainer=$str2)" }
        )
    }

    private companion object {
        private const val TAG = "KeyguardMediaControllerLog"
    }
}
