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

package com.android.systemui.keyguard.domain.interactor

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.data.repository.InWindowLauncherUnlockAnimationRepository
import com.android.systemui.keyguard.data.repository.KeyguardSurfaceBehindRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.shared.system.ActivityManagerWrapper
import com.android.systemui.shared.system.smartspace.SmartspaceState
import com.android.systemui.util.kotlin.sample
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@SysUISingleton
class InWindowLauncherUnlockAnimationInteractor
@Inject
constructor(
    private val repository: InWindowLauncherUnlockAnimationRepository,
    @Application scope: CoroutineScope,
    transitionInteractor: KeyguardTransitionInteractor,
    surfaceBehindRepository: dagger.Lazy<KeyguardSurfaceBehindRepository>,
    private val activityManager: ActivityManagerWrapper,
) {
    val startedUnlockAnimation = repository.startedUnlockAnimation.asStateFlow()

    /**
     * Whether we've STARTED but not FINISHED a transition to GONE, and the preconditions are met to
     * play the in-window unlock animation.
     */
    val transitioningToGoneWithInWindowAnimation: StateFlow<Boolean> =
        transitionInteractor
            .isInTransitionToState(KeyguardState.GONE)
            .sample(repository.launcherActivityClass, ::Pair)
            .map { (isTransitioningToGone, launcherActivityClass) ->
                isTransitioningToGone && isActivityClassUnderneath(launcherActivityClass)
            }
            .stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * Whether we should start the in-window unlock animation.
     *
     * This emits true once the Launcher surface becomes available while we're
     * [transitioningToGoneWithInWindowAnimation].
     */
    val shouldStartInWindowAnimation: StateFlow<Boolean> =
        combine(
                transitioningToGoneWithInWindowAnimation,
                surfaceBehindRepository.get().isSurfaceRemoteAnimationTargetAvailable,
            ) { transitioningWithInWindowAnimation, isSurfaceAvailable ->
                transitioningWithInWindowAnimation && isSurfaceAvailable
            }
            .stateIn(scope, SharingStarted.Eagerly, false)

    /** Sets whether we've started */
    fun setStartedUnlockAnimation(started: Boolean) {
        repository.setStartedUnlockAnimation(started)
    }

    fun setManualUnlockAmount(amount: Float) {
        repository.setManualUnlockAmount(amount)
    }

    fun setLauncherActivityClass(className: String) {
        repository.setLauncherActivityClass(className)
    }

    fun setLauncherSmartspaceState(state: SmartspaceState?) {
        repository.setLauncherSmartspaceState(state)
    }

    /**
     * Whether an activity with the given [activityClass] name is currently underneath the
     * lockscreen (it's at the top of the activity task stack).
     */
    private fun isActivityClassUnderneath(activityClass: String?): Boolean {
        return activityClass?.let {
            activityManager.runningTask?.topActivity?.className?.equals(it)
        }
            ?: false
    }
}
