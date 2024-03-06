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

package com.android.systemui.keyguard.data.repository

import android.app.ActivityManager.RunningTaskInfo
import android.app.WindowConfiguration
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Information about the SHOW_WHEN_LOCKED activity that is either newly on top of the task stack, or
 * newly not on top of the task stack.
 */
data class ShowWhenLockedActivityInfo(
    /** Whether the activity is on top. If not, we're unoccluding and will be animating it out. */
    val isOnTop: Boolean,

    /**
     * Information about the activity, which we use for transition internals and also to customize
     * animations.
     */
    val taskInfo: RunningTaskInfo? = null
) {
    fun isDream(): Boolean {
        return taskInfo?.topActivityType == WindowConfiguration.ACTIVITY_TYPE_DREAM
    }
}

/**
 * Maintains state about "occluding" activities - activities with FLAG_SHOW_WHEN_LOCKED, which are
 * capable of displaying over the lockscreen while the device is still locked (such as Google Maps
 * navigation).
 *
 * Window Manager considers the device to be in the "occluded" state whenever such an activity is on
 * top of the task stack, including while we're unlocked, while keyguard code considers us to be
 * occluded only when we're locked, with an occluding activity currently displaying over the
 * lockscreen.
 *
 * This dual definition is confusing, so this repository collects all of the signals WM gives us,
 * and consolidates them into [showWhenLockedActivityInfo.isOnTop], which is the actual question WM
 * is answering when they say whether we're 'occluded'. Keyguard then uses this signal to
 * conditionally transition to [KeyguardState.OCCLUDED] where appropriate.
 */
@SysUISingleton
class KeyguardOcclusionRepository @Inject constructor() {
    val showWhenLockedActivityInfo = MutableStateFlow(ShowWhenLockedActivityInfo(isOnTop = false))

    /**
     * Sets whether there's a SHOW_WHEN_LOCKED activity on top of the task stack, and optionally,
     * information about the activity itself.
     *
     * If no value is provided for [taskInfo], we'll default to the current [taskInfo].
     *
     * The [taskInfo] is always present when this method is called from the occlude/unocclude
     * animation runners. We use the default when calling from [KeyguardService.isOccluded], since
     * we only receive a true/false value there. isOccluded is mostly redundant - it's almost always
     * called with true after an occlusion animation has started, and with false after an unocclude
     * animation has started. In those cases, we don't want to clear out the taskInfo just because
     * it wasn't available at that call site.
     */
    fun setShowWhenLockedActivityInfo(
        onTop: Boolean,
        taskInfo: RunningTaskInfo? = showWhenLockedActivityInfo.value.taskInfo
    ) {
        showWhenLockedActivityInfo.value =
            ShowWhenLockedActivityInfo(
                isOnTop = onTop,
                taskInfo = taskInfo,
            )
    }
}
