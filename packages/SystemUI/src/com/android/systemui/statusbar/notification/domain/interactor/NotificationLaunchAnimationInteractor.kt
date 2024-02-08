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

package com.android.systemui.statusbar.notification.domain.interactor

import android.util.Log
import com.android.systemui.animation.ActivityTransitionAnimator
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.notification.data.repository.NotificationLaunchAnimationRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/** A repository tracking the status of notification expansion animations. */
@SysUISingleton
class NotificationLaunchAnimationInteractor
@Inject
constructor(private val repository: NotificationLaunchAnimationRepository) {

    /**
     * Emits true if an animation that expands a notification object into an opening window is
     * running and false otherwise.
     *
     * See [com.android.systemui.statusbar.notification.NotificationLaunchAnimatorController].
     */
    val isLaunchAnimationRunning: StateFlow<Boolean>
        get() = repository.isLaunchAnimationRunning

    /** Sets whether the notification expansion launch animation is currently running. */
    fun setIsLaunchAnimationRunning(running: Boolean) {
        if (ActivityTransitionAnimator.DEBUG_TRANSITION_ANIMATION) {
            Log.d(TAG, "setIsLaunchAnimationRunning(running=$running)")
        }
        repository.isLaunchAnimationRunning.value = running
    }

    companion object {
        private const val TAG = "NotificationLaunchAnimationInteractor"
    }
}
