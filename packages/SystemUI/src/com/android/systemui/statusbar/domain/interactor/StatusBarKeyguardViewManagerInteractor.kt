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

package com.android.systemui.statusbar.domain.interactor

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.domain.interactor.KeyguardOcclusionInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardSurfaceBehindInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.domain.interactor.WindowManagerLockscreenVisibilityInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.util.kotlin.sample
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * Whether to set the status bar keyguard view occluded or not, and whether to animate that change.
 */
data class OccludedState(
    val occluded: Boolean,
    val animate: Boolean = false,
)

/** Handles logic around calls to [StatusBarKeyguardViewManager] in legacy code. */
@Deprecated("Will be removed once all of SBKVM's responsibilies are refactored.")
@SysUISingleton
class StatusBarKeyguardViewManagerInteractor
@Inject
constructor(
    keyguardTransitionInteractor: KeyguardTransitionInteractor,
    keyguardOcclusionInteractor: KeyguardOcclusionInteractor,
    powerInteractor: PowerInteractor,
    wmLockscreenVisibilityInteractor: WindowManagerLockscreenVisibilityInteractor,
    surfaceBehindInteractor: KeyguardSurfaceBehindInteractor,
) {
    /** Occlusion state to apply whenever a keyguard transition is STARTED, if any. */
    private val occlusionStateFromStartedStep: Flow<OccludedState> =
        keyguardTransitionInteractor.startedKeyguardTransitionStep
            .sample(powerInteractor.detailedWakefulness, ::Pair)
            .map { (startedStep, wakefulness) ->
                val transitioningFromPowerButtonGesture =
                    KeyguardState.deviceIsAsleepInState(startedStep.from) &&
                        startedStep.to == KeyguardState.OCCLUDED &&
                        wakefulness.powerButtonLaunchGestureTriggered

                if (
                    startedStep.to == KeyguardState.OCCLUDED && !transitioningFromPowerButtonGesture
                ) {
                    // Set occluded upon STARTED, *unless* we're transitioning from the power
                    // button, in which case we're going to play an animation over the lockscreen UI
                    // and need to remain unoccluded until the transition finishes.
                    return@map OccludedState(occluded = true, animate = false)
                }

                if (
                    startedStep.from == KeyguardState.OCCLUDED &&
                        startedStep.to == KeyguardState.LOCKSCREEN
                ) {
                    // Set unoccluded immediately ONLY if we're transitioning back to the lockscreen
                    // since we need the views visible to animate them back down. This is a special
                    // case due to the way unocclusion remote animations are run. We can remove this
                    // once the unocclude animation uses the return animation framework.
                    return@map OccludedState(occluded = false, animate = false)
                }

                // Otherwise, wait for the transition to FINISH to decide.
                return@map null
            }
            .filterNotNull()

    /** Occlusion state to apply whenever a keyguard transition is FINISHED. */
    private val occlusionStateFromFinishedStep =
        keyguardTransitionInteractor.finishedKeyguardTransitionStep
            .sample(keyguardOcclusionInteractor.isShowWhenLockedActivityOnTop, ::Pair)
            .map { (finishedStep, showWhenLockedOnTop) ->
                // If we're FINISHED in OCCLUDED, we want to render as occluded. We also need to
                // remain occluded if a SHOW_WHEN_LOCKED activity is on the top of the task stack,
                // and we're in any state other than GONE. This is necessary, for example, when we
                // transition from OCCLUDED to a bouncer state. Otherwise, we should not be
                // occluded.
                val occluded =
                    finishedStep.to == KeyguardState.OCCLUDED ||
                        (showWhenLockedOnTop && finishedStep.to != KeyguardState.GONE)
                OccludedState(occluded = occluded, animate = false)
            }

    /** Occlusion state to apply to SBKVM's setOccluded call. */
    val keyguardViewOcclusionState =
        merge(occlusionStateFromStartedStep, occlusionStateFromFinishedStep)
            .distinctUntilChangedBy {
                // Don't switch 'animate' values mid-transition.
                it.occluded
            }

    /** Visibility state to apply to SBKVM via show() and hide(). */
    val keyguardViewVisibility =
        combine(
                wmLockscreenVisibilityInteractor.lockscreenVisibility,
                surfaceBehindInteractor.isAnimatingSurface,
            ) { lockscreenVisible, animatingSurface ->
                lockscreenVisible || animatingSurface
            }
            .distinctUntilChanged()
}
