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

package com.android.systemui.statusbar.notification.data.repository

import android.util.Log
import com.android.systemui.animation.ActivityLaunchAnimator
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "NotificationExpansionRepository"

/** A repository tracking the status of notification expansion animations. */
@SysUISingleton
class NotificationExpansionRepository @Inject constructor() {
    private val _isExpandAnimationRunning = MutableStateFlow(false)

    /**
     * Emits true if an animation that expands a notification object into an opening window is
     * running and false otherwise.
     *
     * See [com.android.systemui.statusbar.notification.NotificationLaunchAnimatorController].
     */
    val isExpandAnimationRunning: Flow<Boolean> = _isExpandAnimationRunning.asStateFlow()

    /** Sets whether the notification expansion animation is currently running. */
    fun setIsExpandAnimationRunning(running: Boolean) {
        if (ActivityLaunchAnimator.DEBUG_LAUNCH_ANIMATION) {
            Log.d(TAG, "setIsExpandAnimationRunning(running=$running)")
        }
        _isExpandAnimationRunning.value = running
    }
}
