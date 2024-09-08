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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.keyguard.domain.interactor

import com.android.compose.animation.scene.ObservableTransitionState
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryInteractor
import com.android.systemui.keyguard.data.repository.KeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.BiometricUnlockMode
import com.android.systemui.keyguard.shared.model.Edge
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.KeyguardState.Companion.deviceIsAsleepInState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.statusbar.notification.domain.interactor.NotificationLaunchAnimationInteractor
import com.android.systemui.util.kotlin.Utils.Companion.toTriple
import com.android.systemui.util.kotlin.sample
import com.android.systemui.utils.coroutines.flow.flatMapLatestConflated
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class WindowManagerLockscreenVisibilityInteractor
@Inject
constructor(
    keyguardInteractor: KeyguardInteractor,
    transitionRepository: KeyguardTransitionRepository,
    transitionInteractor: KeyguardTransitionInteractor,
    surfaceBehindInteractor: KeyguardSurfaceBehindInteractor,
    fromLockscreenInteractor: FromLockscreenTransitionInteractor,
    fromBouncerInteractor: FromPrimaryBouncerTransitionInteractor,
    fromAlternateBouncerInteractor: FromAlternateBouncerTransitionInteractor,
    notificationLaunchAnimationInteractor: NotificationLaunchAnimationInteractor,
    sceneInteractor: Lazy<SceneInteractor>,
    deviceEntryInteractor: Lazy<DeviceEntryInteractor>,
    wakeToGoneInteractor: KeyguardWakeDirectlyToGoneInteractor,
) {
    private val defaultSurfaceBehindVisibility =
        combine(
            transitionInteractor.isFinishedIn(
                scene = Scenes.Gone,
                stateWithoutSceneContainer = KeyguardState.GONE
            ),
            wakeToGoneInteractor.canWakeDirectlyToGone,
        ) { isOnGone, canWakeDirectlyToGone ->
            isOnGone || canWakeDirectlyToGone
        }

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
                SceneContainerFlag.assertInLegacyMode()
                when (startedStep.from) {
                    KeyguardState.LOCKSCREEN -> {
                        fromLockscreenInteractor.surfaceBehindVisibility
                    }
                    KeyguardState.PRIMARY_BOUNCER -> {
                        fromBouncerInteractor.surfaceBehindVisibility
                    }
                    KeyguardState.ALTERNATE_BOUNCER -> {
                        fromAlternateBouncerInteractor.surfaceBehindVisibility
                    }
                    KeyguardState.OCCLUDED -> {
                        // OCCLUDED -> GONE occurs when an app is on top of the keyguard, and then
                        // requests manual dismissal of the keyguard in the background. The app will
                        // remain visible on top of the stack throughout this transition, so we
                        // should not trigger the keyguard going away animation by returning
                        // surfaceBehindVisibility = true.
                        flowOf(false)
                    }
                    else -> flowOf(null)
                }
            }
            .distinctUntilChanged()

    private val isDeviceEntered: Flow<Boolean> by lazy {
        deviceEntryInteractor.get().isDeviceEntered
    }

    private val isDeviceNotEntered: Flow<Boolean> by lazy { isDeviceEntered.map { !it } }

    /**
     * Surface visibility, which is either determined by the default visibility when not
     * transitioning between [KeyguardState]s or [Scenes] or the transition-specific visibility used
     * during certain ongoing transitions.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val surfaceBehindVisibility: Flow<Boolean> =
        if (SceneContainerFlag.isEnabled) {
                sceneInteractor.get().transitionState.flatMapLatestConflated { transitionState ->
                    when (transitionState) {
                        is ObservableTransitionState.Transition ->
                            when {
                                transitionState.fromContent == Scenes.Lockscreen &&
                                    transitionState.toContent == Scenes.Gone ->
                                    sceneInteractor
                                        .get()
                                        .isTransitionUserInputOngoing
                                        .flatMapLatestConflated { isUserInputOngoing ->
                                            if (isUserInputOngoing) {
                                                isDeviceEntered
                                            } else {
                                                flowOf(true)
                                            }
                                        }
                                transitionState.fromContent == Scenes.Bouncer &&
                                    transitionState.toContent == Scenes.Gone ->
                                    transitionState.progress.map { progress ->
                                        progress >
                                            FromPrimaryBouncerTransitionInteractor
                                                .TO_GONE_SURFACE_BEHIND_VISIBLE_THRESHOLD
                                    }
                                else -> isDeviceEntered
                            }
                        is ObservableTransitionState.Idle -> isDeviceEntered
                    }
                }
            } else {
                transitionInteractor.isInTransition.flatMapLatest { isInTransition ->
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
            }
            .distinctUntilChanged()

    /**
     * Whether we're animating, or intend to animate, the surface behind the keyguard via remote
     * animation. This is used to keep the RemoteAnimationTarget alive until we're done using it.
     */
    val usingKeyguardGoingAwayAnimation: Flow<Boolean> =
        if (SceneContainerFlag.isEnabled) {
            combine(
                    sceneInteractor.get().transitionState,
                    surfaceBehindInteractor.isAnimatingSurface,
                    notificationLaunchAnimationInteractor.isLaunchAnimationRunning,
                ) { transition, isAnimatingSurface, isLaunchAnimationRunning ->
                    // Using the animation if we're animating it directly, or if the
                    // ActivityLaunchAnimator is in the process of animating it.
                    val isAnyAnimationRunning = isAnimatingSurface || isLaunchAnimationRunning
                    // We may still be animating the surface after the keyguard is fully GONE, since
                    // some animations (like the translation spring) are not tied directly to the
                    // transition step amount.
                    transition.isTransitioning(to = Scenes.Gone) ||
                        (isAnyAnimationRunning &&
                            (transition.isIdle(Scenes.Gone) ||
                                transition.isTransitioning(from = Scenes.Gone)))
                }
                .distinctUntilChanged()
        } else {
            combine(
                    transitionInteractor.isInTransition(
                        edge = Edge.create(to = Scenes.Gone),
                        edgeWithoutSceneContainer = Edge.create(to = KeyguardState.GONE)
                    ),
                    transitionInteractor.isFinishedIn(
                        scene = Scenes.Gone,
                        stateWithoutSceneContainer = KeyguardState.GONE
                    ),
                    surfaceBehindInteractor.isAnimatingSurface,
                    notificationLaunchAnimationInteractor.isLaunchAnimationRunning,
                ) { isInTransitionToGone, isOnGone, isAnimatingSurface, notifLaunchRunning ->
                    // Using the animation if we're animating it directly, or if the
                    // ActivityLaunchAnimator is in the process of animating it.
                    val animationsRunning = isAnimatingSurface || notifLaunchRunning
                    // We may still be animating the surface after the keyguard is fully GONE, since
                    // some animations (like the translation spring) are not tied directly to the
                    // transition step amount.
                    isInTransitionToGone || (isOnGone && animationsRunning)
                }
                .distinctUntilChanged()
        }

    /**
     * Whether the lockscreen is visible, from the Window Manager (WM) perspective.
     *
     * Note: This may briefly be true even if the lockscreen UI has animated out (alpha = 0f), as we
     * only inform WM once we're done with the keyguard and we're fully GONE. Don't use this if you
     * want to know if the AOD/clock/notifs/etc. are visible.
     */
    val lockscreenVisibility: Flow<Boolean> =
        if (SceneContainerFlag.isEnabled) {
            isDeviceNotEntered
        } else {
            combine(
                    transitionInteractor.currentKeyguardState,
                    wakeToGoneInteractor.canWakeDirectlyToGone,
                    ::Pair
                )
                .sample(transitionInteractor.startedStepWithPrecedingStep, ::toTriple)
                .map { (currentState, canWakeDirectlyToGone, startedWithPrev) ->
                    val startedFromStep = startedWithPrev.previousValue
                    val startedStep = startedWithPrev.newValue
                    val returningToGoneAfterCancellation =
                        startedStep.to == KeyguardState.GONE &&
                            startedFromStep.transitionState == TransitionState.CANCELED &&
                            startedFromStep.from == KeyguardState.GONE

                    val transitionInfo = transitionRepository.currentTransitionInfoInternal.value
                    val wakingDirectlyToGone =
                        deviceIsAsleepInState(transitionInfo.from) &&
                            transitionInfo.to == KeyguardState.GONE

                    if (returningToGoneAfterCancellation || wakingDirectlyToGone) {
                        // GONE -> AOD/DOZING (cancel) -> GONE is the camera launch transition,
                        // which means we never want to show the lockscreen throughout the
                        // transition. Same for waking directly to gone, due to the lockscreen being
                        // disabled or because the device was woken back up before the lock timeout
                        // duration elapsed.
                        false
                    } else if (canWakeDirectlyToGone) {
                        // Never show the lockscreen if we can wake directly to GONE. This means
                        // that the lock timeout has not yet elapsed, or the keyguard is disabled.
                        // In either case, we don't show the activity lock screen until one of those
                        // conditions changes.
                        false
                    } else if (
                        currentState == KeyguardState.DREAMING &&
                            deviceEntryInteractor.get().isUnlocked.value
                    ) {
                        // Dreams dismiss keyguard and return to GONE if they can.
                        false
                    } else if (
                        startedWithPrev.newValue.from == KeyguardState.OCCLUDED &&
                            startedWithPrev.newValue.to == KeyguardState.GONE
                    ) {
                        // OCCLUDED -> GONE directly, without transiting a *_BOUNCER state, occurs
                        // when an app uses intent flags to launch over an insecure keyguard without
                        // dismissing it, and then manually requests keyguard dismissal while
                        // OCCLUDED. This transition is not user-visible; the device unlocks in the
                        // background and the app remains on top, while we're now GONE. In this case
                        // we should simply tell WM that the lockscreen is no longer visible, and
                        // *not* play the going away animation or related animations.
                        false
                    } else {
                        currentState != KeyguardState.GONE
                    }
                }
                .distinctUntilChanged()
        }

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
                keyguardInteractor.isAodAvailable,
                keyguardInteractor.biometricUnlockState,
            ) { isDozing, isAodAvailable, biometricUnlockState ->
                // AOD is visible if we're dozing, unless we are wake and unlocking (where we go
                // directly from AOD to unlocked while dozing).
                isDozing &&
                    isAodAvailable &&
                    !BiometricUnlockMode.isWakeAndUnlock(biometricUnlockState.mode)
            }
            .distinctUntilChanged()
}
