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

package com.android.systemui.accessibility.hearingaid

import android.annotation.IntDef
import com.android.internal.logging.UiEventLogger
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject

@SysUISingleton
class HearingDevicesUiEventLogger @Inject constructor(private val uiEventLogger: UiEventLogger) {

    /** Logs the given event */
    fun log(event: UiEventLogger.UiEventEnum, launchSourceId: Int) {
        log(event, launchSourceId, null)
    }

    fun log(event: UiEventLogger.UiEventEnum, launchSourceId: Int, pkgName: String?) {
        uiEventLogger.log(event, launchSourceId, pkgName)
    }

    /**
     * The possible launch source of hearing devices dialog
     *
     * @hide
     */
    @IntDef(LAUNCH_SOURCE_UNKNOWN, LAUNCH_SOURCE_A11Y, LAUNCH_SOURCE_QS_TILE)
    annotation class LaunchSourceId

    companion object {
        const val LAUNCH_SOURCE_UNKNOWN = 0
        const val LAUNCH_SOURCE_A11Y = 1 // launch from AccessibilityManagerService
        const val LAUNCH_SOURCE_QS_TILE = 2
    }
}
