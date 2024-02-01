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
 *
 */

package com.android.systemui.keyguard.ui.viewmodel

import android.graphics.Point
import android.view.View.VISIBLE
import com.android.systemui.Flags.newAodTransition
import com.android.systemui.common.shared.model.NotificationContainerBounds
import com.android.systemui.communal.domain.interactor.CommunalInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.KeyguardState.AOD
import com.android.systemui.keyguard.shared.model.KeyguardState.GONE
import com.android.systemui.keyguard.shared.model.KeyguardState.LOCKSCREEN
import com.android.systemui.keyguard.shared.model.TransitionState.RUNNING
import com.android.systemui.keyguard.shared.model.TransitionState.STARTED
import com.android.systemui.statusbar.notification.domain.interactor.NotificationsKeyguardInteractor
import com.android.systemui.statusbar.phone.DozeParameters
import com.android.systemui.statusbar.phone.ScreenOffAnimationController
import com.android.systemui.util.kotlin.pairwise
import com.android.systemui.util.kotlin.sample
import com.android.systemui.util.ui.AnimatableEvent
import com.android.systemui.util.ui.AnimatedValue
import com.android.systemui.util.ui.toAnimatedValueFlow
import com.android.systemui.util.ui.zip
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart

@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class KeyguardRootViewModel
@Inject
constructor(
    private val deviceEntryInteractor: DeviceEntryInteractor,
    private val dozeParameters: DozeParameters,
    private val keyguardInteractor: KeyguardInteractor,
    communalInteractor: CommunalInteractor,
    keyguardTransitionInteractor: KeyguardTransitionInteractor,
    private val notificationsKeyguardInteractor: NotificationsKeyguardInteractor,
    aodToLockscreenTransitionViewModel: AodToLockscreenTransitionViewModel,
    lockscreenToGoneTransitionViewModel: LockscreenToGoneTransitionViewModel,
    alternateBouncerToGoneTransitionViewModel: AlternateBouncerToGoneTransitionViewModel,
    primaryBouncerToGoneTransitionViewModel: PrimaryBouncerToGoneTransitionViewModel,
    lockscreenToGlanceableHubTransitionViewModel: LockscreenToGlanceableHubTransitionViewModel,
    glanceableHubToLockscreenTransitionViewModel: GlanceableHubToLockscreenTransitionViewModel,
    screenOffAnimationController: ScreenOffAnimationController,
    private val aodBurnInViewModel: AodBurnInViewModel,
    aodAlphaViewModel: AodAlphaViewModel,
) {

    val burnInLayerVisibility: Flow<Int> =
        keyguardTransitionInteractor.startedKeyguardState
            .filter { it == AOD || it == LOCKSCREEN }
            .map { VISIBLE }

    val goneToAodTransition = keyguardTransitionInteractor.transition(from = GONE, to = AOD)

    private val goneToAodTransitionRunning: Flow<Boolean> =
        goneToAodTransition
            .map { it.transitionState == STARTED || it.transitionState == RUNNING }
            .onStart { emit(false) }
            .distinctUntilChanged()

    /** Last point that the root view was tapped */
    val lastRootViewTapPosition: Flow<Point?> = keyguardInteractor.lastRootViewTapPosition

    /** the shared notification container bounds *on the lockscreen* */
    val notificationBounds: StateFlow<NotificationContainerBounds> =
        keyguardInteractor.notificationContainerBounds

    /**
     * The keyguard root view can be clipped as the shade is pulled down, typically only for
     * non-split shade cases.
     */
    val topClippingBounds: Flow<Int?> = keyguardInteractor.topClippingBounds

    /** An observable for the alpha level for the entire keyguard root view. */
    val alpha: Flow<Float> =
        combine(
                communalInteractor.isIdleOnCommunal,
                // The transitions are mutually exclusive, so they are safe to merge to get the last
                // value emitted by any of them. Do not add flows that cannot make this guarantee.
                merge(
                    aodAlphaViewModel.alpha,
                    lockscreenToGlanceableHubTransitionViewModel.keyguardAlpha,
                    glanceableHubToLockscreenTransitionViewModel.keyguardAlpha,
                    lockscreenToGoneTransitionViewModel.lockscreenAlpha,
                    primaryBouncerToGoneTransitionViewModel.lockscreenAlpha,
                    alternateBouncerToGoneTransitionViewModel.lockscreenAlpha,
                )
            ) { isIdleOnCommunal, alpha ->
                if (isIdleOnCommunal) {
                    // Keyguard should not show while the communal hub is fully visible. This check
                    // is added since at the moment, closing the notification shade will cause the
                    // keyguard alpha to be set back to 1.
                    0f
                } else {
                    alpha
                }
            }
            .distinctUntilChanged()

    /** Specific alpha value for elements visible during [KeyguardState.LOCKSCREEN] */
    val lockscreenStateAlpha: Flow<Float> = aodToLockscreenTransitionViewModel.lockscreenAlpha

    /** For elements that appear and move during the animation -> AOD */
    val burnInLayerAlpha: Flow<Float> = aodBurnInViewModel.alpha

    fun translationY(params: BurnInParameters): Flow<Float> {
        return aodBurnInViewModel.translationY(params)
    }

    fun translationX(params: BurnInParameters): Flow<Float> {
        return aodBurnInViewModel.translationX(params)
    }

    fun scale(params: BurnInParameters): Flow<BurnInScaleViewModel> {
        return aodBurnInViewModel.scale(params)
    }

    /** Is the notification icon container visible? */
    val isNotifIconContainerVisible: Flow<AnimatedValue<Boolean>> =
        combine(
                goneToAodTransitionRunning,
                keyguardTransitionInteractor.finishedKeyguardState.map {
                    KeyguardState.lockscreenVisibleInState(it)
                },
                deviceEntryInteractor.isBypassEnabled,
                areNotifsFullyHiddenAnimated(),
                isPulseExpandingAnimated(),
            ) {
                goneToAodTransitionRunning: Boolean,
                onKeyguard: Boolean,
                isBypassEnabled: Boolean,
                notifsFullyHidden: AnimatedValue<Boolean>,
                pulseExpanding: AnimatedValue<Boolean>,
                ->
                when {
                    // Hide the AOD icons if we're not in the KEYGUARD state unless the screen off
                    // animation is playing, in which case we want them to be visible if we're
                    // animating in the AOD UI and will be switching to KEYGUARD shortly.
                    goneToAodTransitionRunning ||
                        (!onKeyguard &&
                            !screenOffAnimationController.shouldShowAodIconsWhenShade()) ->
                        AnimatedValue.NotAnimating(false)
                    else ->
                        zip(notifsFullyHidden, pulseExpanding) {
                            areNotifsFullyHidden,
                            isPulseExpanding,
                            ->
                            when {
                                // If we're bypassing, then we're visible
                                isBypassEnabled -> true
                                // If we are pulsing (and not bypassing), then we are hidden
                                isPulseExpanding -> false
                                // If notifs are fully gone, then we're visible
                                areNotifsFullyHidden -> true
                                // Otherwise, we're hidden
                                else -> false
                            }
                        }
                }
            }
            .distinctUntilChanged()

    fun onNotificationContainerBoundsChanged(top: Float, bottom: Float) {
        keyguardInteractor.setNotificationContainerBounds(
            NotificationContainerBounds(top = top, bottom = bottom)
        )
    }

    /** Is there an expanded pulse, are we animating in response? */
    private fun isPulseExpandingAnimated(): Flow<AnimatedValue<Boolean>> {
        return notificationsKeyguardInteractor.isPulseExpanding
            .pairwise(initialValue = null)
            // If pulsing changes, start animating, unless it's the first emission
            .map { (prev, expanding) -> AnimatableEvent(expanding, startAnimating = prev != null) }
            .toAnimatedValueFlow()
    }

    /** Are notifications completely hidden from view, are we animating in response? */
    private fun areNotifsFullyHiddenAnimated(): Flow<AnimatedValue<Boolean>> {
        return notificationsKeyguardInteractor.areNotificationsFullyHidden
            .pairwise(initialValue = null)
            .sample(deviceEntryInteractor.isBypassEnabled) { (prev, fullyHidden), bypassEnabled ->
                val animate =
                    when {
                        // Don't animate for the first value
                        prev == null -> false
                        // Always animate if bypass is enabled.
                        bypassEnabled -> true
                        // If we're not bypassing and we're not going to AOD, then we're not
                        // animating.
                        !dozeParameters.alwaysOn -> false
                        // Don't animate when going to AOD if the display needs blanking.
                        dozeParameters.displayNeedsBlanking -> false
                        // We only want the appear animations to happen when the notifications
                        // get fully hidden, since otherwise the un-hide animation overlaps.
                        newAodTransition() -> true
                        else -> fullyHidden
                    }
                AnimatableEvent(fullyHidden, animate)
            }
            .toAnimatedValueFlow()
    }

    fun setRootViewLastTapPosition(point: Point) {
        keyguardInteractor.setLastRootViewTapPosition(point)
    }
}
