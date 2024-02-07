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
import com.android.systemui.keyguard.shared.model.BiometricUnlockModel
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.statusbar.notification.domain.interactor.NotificationLaunchAnimationInteractor
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

@SysUISingleton
class WindowManagerLockscreenVisibilityInteractor
@Inject
constructor(
    keyguardInteractor: KeyguardInteractor,
    transitionInteractor: KeyguardTransitionInteractor,
    surfaceBehindInteractor: KeyguardSurfaceBehindInteractor,
    fromLockscreenInteractor: FromLockscreenTransitionInteractor,
    fromBouncerInteractor: FromPrimaryBouncerTransitionInteractor,
    notificationLaunchAnimationInteractor: NotificationLaunchAnimationInteractor,
) {
    private val defaultSurfaceBehindVisibility =
        transitionInteractor.finishedKeyguardState.map(::isSurfaceVisible)

    /**
     * Surface visibility provided by the From*TransitionInteractor responsible for the currently
     * RUNNING transition, or null if the current transition does not require special surface
     * visibility handling.
     *
     * An example of transition-specific visibility is swipe to unlock, where the surface should
     * only be visible after swiping 20% of the way up the screen, and should become invisible again
     * if the user swipes back down.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val transitionSpecificSurfaceBehindVisibility: Flow<Boolean?> =
        transitionInteractor.startedKeyguardTransitionStep
            .flatMapLatest { startedStep ->
                when (startedStep.from) {
                    KeyguardState.LOCKSCREEN -> {
                        fromLockscreenInteractor.surfaceBehindVisibility
                    }
                    KeyguardState.PRIMARY_BOUNCER -> {
                        fromBouncerInteractor.surfaceBehindVisibility
                    }
                    else -> flowOf(null)
                }
            }
            .distinctUntilChanged()

    /**
     * Surface visibility, which is either determined by the default visibility in the FINISHED
     * KeyguardState, or the transition-specific visibility used during certain RUNNING transitions.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val surfaceBehindVisibility: Flow<Boolean> =
        transitionInteractor.isInTransitionToAnyState
            .flatMapLatest { isInTransition ->
                if (!isInTransition) {
                    defaultSurfaceBehindVisibility
                } else {
                    combine(
                        transitionSpecificSurfaceBehindVisibility,
                        defaultSurfaceBehindVisibility,
                    ) { transitionVisibility, defaultVisibility ->
                        // Defer to the transition-specific visibility since we're RUNNING a
                        // transition, but fall back to the default visibility if the current
                        // transition's interactor did not specify a visibility.
                        transitionVisibility ?: defaultVisibility
                    }
                }
            }
            .distinctUntilChanged()

    /**
     * Whether we're animating, or intend to animate, the surface behind the keyguard via remote
     * animation. This is used to keep the RemoteAnimationTarget alive until we're done using it.
     */
    val usingKeyguardGoingAwayAnimation: Flow<Boolean> =
        combine(
                transitionInteractor.isInTransitionToState(KeyguardState.GONE),
                transitionInteractor.finishedKeyguardState,
                surfaceBehindInteractor.isAnimatingSurface,
                notificationLaunchAnimationInteractor.isLaunchAnimationRunning,
            ) { isInTransitionToGone, finishedState, isAnimatingSurface, notifLaunchRunning ->
                // Using the animation if we're animating it directly, or if the
                // ActivityLaunchAnimator is in the process of animating it.
                val animationsRunning = isAnimatingSurface || notifLaunchRunning
                // We may still be animating the surface after the keyguard is fully GONE, since
                // some animations (like the translation spring) are not tied directly to the
                // transition step amount.
                isInTransitionToGone || (finishedState == KeyguardState.GONE && animationsRunning)
            }
            .distinctUntilChanged()

    /**
     * Whether the lockscreen is visible, from the Window Manager (WM) perspective.
     *
     * Note: This may briefly be true even if the lockscreen UI has animated out (alpha = 0f), as we
     * only inform WM once we're done with the keyguard and we're fully GONE. Don't use this if you
     * want to know if the AOD/clock/notifs/etc. are visible.
     */
    val lockscreenVisibility: Flow<Boolean> =
        combine(
                transitionInteractor.startedKeyguardTransitionStep,
                transitionInteractor.finishedKeyguardState,
            ) { startedStep, finishedState ->
                // If we finished the transition, use the finished state. If we're running a
                // transition, use the state we're transitioning FROM. This can be different from
                // the last finished state if a transition is interrupted. For example, if we were
                // transitioning from GONE to AOD and then started AOD -> LOCKSCREEN mid-transition,
                // we want to immediately use the visibility for AOD (lockscreenVisibility=true)
                // even though the lastFinishedState is still GONE (lockscreenVisibility=false).
                if (finishedState == startedStep.to) finishedState else startedStep.from
            }
            .map(KeyguardState::lockscreenVisibleInState)
            .distinctUntilChanged()

    /**
     * Whether always-on-display (AOD) is visible when the lockscreen is visible, from window
     * manager's perspective.
     *
     * Note: This may be true even if AOD is not user-visible, such as when the light sensor
     * indicates the device is in the user's pocket. Don't use this if you want to know if the AOD
     * clock/smartspace/notif icons are visible.
     */
    val aodVisibility: Flow<Boolean> =
        combine(
                keyguardInteractor.isDozing,
                keyguardInteractor.biometricUnlockState,
            ) { isDozing, biometricUnlockState ->
                // AOD is visible if we're dozing, unless we are wake and unlocking (where we go
                // directly from AOD to unlocked while dozing).
                isDozing && !BiometricUnlockModel.isWakeAndUnlock(biometricUnlockState)
            }
            .distinctUntilChanged()

    companion object {
        fun isSurfaceVisible(state: KeyguardState): Boolean {
            return !KeyguardState.lockscreenVisibleInState(state)
        }
    }
}
