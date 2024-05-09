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
import android.util.MathUtils
import android.view.View.VISIBLE
import com.android.app.tracing.coroutines.launch
import com.android.systemui.Flags.newAodTransition
import com.android.systemui.common.shared.model.NotificationContainerBounds
import com.android.systemui.communal.domain.interactor.CommunalInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.BurnInModel
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.KeyguardState.AOD
import com.android.systemui.keyguard.shared.model.KeyguardState.GONE
import com.android.systemui.keyguard.shared.model.KeyguardState.LOCKSCREEN
import com.android.systemui.keyguard.shared.model.KeyguardState.OCCLUDED
import com.android.systemui.keyguard.shared.model.TransitionState.RUNNING
import com.android.systemui.keyguard.shared.model.TransitionState.STARTED
import com.android.systemui.keyguard.ui.StateToValue
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.notification.domain.interactor.NotificationsKeyguardInteractor
import com.android.systemui.statusbar.phone.DozeParameters
import com.android.systemui.statusbar.phone.ScreenOffAnimationController
import com.android.systemui.util.kotlin.BooleanFlowOperators.anyOf
import com.android.systemui.util.kotlin.pairwise
import com.android.systemui.util.kotlin.sample
import com.android.systemui.util.ui.AnimatableEvent
import com.android.systemui.util.ui.AnimatedValue
import com.android.systemui.util.ui.toAnimatedValueFlow
import com.android.systemui.util.ui.zip
import javax.inject.Inject
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.combineTransform
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart

@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class KeyguardRootViewModel
@Inject
constructor(
    @Application private val scope: CoroutineScope,
    private val deviceEntryInteractor: DeviceEntryInteractor,
    private val dozeParameters: DozeParameters,
    private val keyguardInteractor: KeyguardInteractor,
    private val communalInteractor: CommunalInteractor,
    private val keyguardTransitionInteractor: KeyguardTransitionInteractor,
    private val notificationsKeyguardInteractor: NotificationsKeyguardInteractor,
    private val alternateBouncerToGoneTransitionViewModel:
        AlternateBouncerToGoneTransitionViewModel,
    private val aodToGoneTransitionViewModel: AodToGoneTransitionViewModel,
    private val aodToLockscreenTransitionViewModel: AodToLockscreenTransitionViewModel,
    private val aodToOccludedTransitionViewModel: AodToOccludedTransitionViewModel,
    private val dozingToGoneTransitionViewModel: DozingToGoneTransitionViewModel,
    private val dozingToLockscreenTransitionViewModel: DozingToLockscreenTransitionViewModel,
    private val dozingToOccludedTransitionViewModel: DozingToOccludedTransitionViewModel,
    private val dreamingToLockscreenTransitionViewModel: DreamingToLockscreenTransitionViewModel,
    private val dreamingToGoneTransitionViewModel: DreamingToGoneTransitionViewModel,
    private val glanceableHubToLockscreenTransitionViewModel:
        GlanceableHubToLockscreenTransitionViewModel,
    private val goneToAodTransitionViewModel: GoneToAodTransitionViewModel,
    private val goneToDozingTransitionViewModel: GoneToDozingTransitionViewModel,
    private val goneToDreamingTransitionViewModel: GoneToDreamingTransitionViewModel,
    private val goneToLockscreenTransitionViewModel: GoneToLockscreenTransitionViewModel,
    private val lockscreenToAodTransitionViewModel: LockscreenToAodTransitionViewModel,
    private val lockscreenToDozingTransitionViewModel: LockscreenToDozingTransitionViewModel,
    private val lockscreenToDreamingTransitionViewModel: LockscreenToDreamingTransitionViewModel,
    private val lockscreenToGlanceableHubTransitionViewModel:
        LockscreenToGlanceableHubTransitionViewModel,
    private val lockscreenToGoneTransitionViewModel: LockscreenToGoneTransitionViewModel,
    private val lockscreenToOccludedTransitionViewModel: LockscreenToOccludedTransitionViewModel,
    private val lockscreenToPrimaryBouncerTransitionViewModel:
        LockscreenToPrimaryBouncerTransitionViewModel,
    private val occludedToAodTransitionViewModel: OccludedToAodTransitionViewModel,
    private val occludedToDozingTransitionViewModel: OccludedToDozingTransitionViewModel,
    private val occludedToLockscreenTransitionViewModel: OccludedToLockscreenTransitionViewModel,
    private val primaryBouncerToAodTransitionViewModel: PrimaryBouncerToAodTransitionViewModel,
    private val primaryBouncerToGoneTransitionViewModel: PrimaryBouncerToGoneTransitionViewModel,
    private val primaryBouncerToLockscreenTransitionViewModel:
        PrimaryBouncerToLockscreenTransitionViewModel,
    private val screenOffAnimationController: ScreenOffAnimationController,
    private val aodBurnInViewModel: AodBurnInViewModel,
    private val aodAlphaViewModel: AodAlphaViewModel,
    private val shadeInteractor: ShadeInteractor,
) {
    private var burnInJob: Job? = null
    private val burnInModel = MutableStateFlow(BurnInModel())

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

    private val isOnLockscreen: Flow<Boolean> =
        combine(
                keyguardTransitionInteractor.isFinishedInState(LOCKSCREEN).onStart { emit(false) },
                anyOf(
                    keyguardTransitionInteractor.isInTransitionToState(LOCKSCREEN),
                    keyguardTransitionInteractor.isInTransitionFromState(LOCKSCREEN),
                ),
            ) { onLockscreen, transitioningToOrFromLockscreen ->
                onLockscreen || transitioningToOrFromLockscreen
            }
            .distinctUntilChanged()

    private val alphaOnShadeExpansion: Flow<Float> =
        combineTransform(
                keyguardTransitionInteractor.isInTransition(from = LOCKSCREEN, to = GONE),
                isOnLockscreen,
                shadeInteractor.qsExpansion,
                shadeInteractor.shadeExpansion,
            ) { lockscreenToGoneTransitionRunning, isOnLockscreen, qsExpansion, shadeExpansion ->
                // Fade out quickly as the shade expands
                if (isOnLockscreen && !lockscreenToGoneTransitionRunning) {
                    val alpha =
                        1f -
                            MathUtils.constrainedMap(
                                /* rangeMin = */ 0f,
                                /* rangeMax = */ 1f,
                                /* valueMin = */ 0f,
                                /* valueMax = */ 0.2f,
                                /* value = */ max(qsExpansion, shadeExpansion)
                            )
                    emit(alpha)
                }
            }
            .distinctUntilChanged()

    /**
     * Keyguard should not show while the communal hub is fully visible. This check is added since
     * at the moment, closing the notification shade will cause the keyguard alpha to be set back to
     * 1. Also ensure keyguard is never visible when GONE.
     */
    private val hideKeyguard: Flow<Boolean> =
        combine(
                communalInteractor.isIdleOnCommunal,
                keyguardTransitionInteractor
                    .transitionValue(GONE)
                    .map { it == 1f }
                    .onStart { emit(false) },
                keyguardTransitionInteractor
                    .transitionValue(OCCLUDED)
                    .map { it == 1f }
                    .onStart { emit(false) },
                keyguardTransitionInteractor
                    .transitionValue(KeyguardState.DREAMING)
                    .map { it == 1f }
                    .onStart { emit(false) },
            ) { isIdleOnCommunal, isGone, isOccluded, isDreaming ->
                isIdleOnCommunal || isGone || isOccluded || isDreaming
            }
            .distinctUntilChanged()

    /** Last point that the root view was tapped */
    val lastRootViewTapPosition: Flow<Point?> = keyguardInteractor.lastRootViewTapPosition

    /**
     * The keyguard root view can be clipped as the shade is pulled down, typically only for
     * non-split shade cases.
     */
    val topClippingBounds: Flow<Int?> = keyguardInteractor.topClippingBounds

    /** An observable for the alpha level for the entire keyguard root view. */
    fun alpha(viewState: ViewStateAccessor): Flow<Float> {
        return combine(
                hideKeyguard,
                // The transitions are mutually exclusive, so they are safe to merge to get the last
                // value emitted by any of them. Do not add flows that cannot make this guarantee.
                merge(
                        alphaOnShadeExpansion,
                        keyguardInteractor.dismissAlpha.filterNotNull(),
                        alternateBouncerToGoneTransitionViewModel.lockscreenAlpha(viewState),
                        aodToGoneTransitionViewModel.lockscreenAlpha(viewState),
                        aodToLockscreenTransitionViewModel.lockscreenAlpha(viewState),
                        aodToOccludedTransitionViewModel.lockscreenAlpha(viewState),
                        dozingToGoneTransitionViewModel.lockscreenAlpha(viewState),
                        dozingToLockscreenTransitionViewModel.lockscreenAlpha,
                        dozingToOccludedTransitionViewModel.lockscreenAlpha(viewState),
                        dreamingToGoneTransitionViewModel.lockscreenAlpha,
                        dreamingToLockscreenTransitionViewModel.lockscreenAlpha,
                        glanceableHubToLockscreenTransitionViewModel.keyguardAlpha,
                        goneToAodTransitionViewModel.enterFromTopAnimationAlpha,
                        goneToDozingTransitionViewModel.lockscreenAlpha,
                        goneToDreamingTransitionViewModel.lockscreenAlpha,
                        goneToLockscreenTransitionViewModel.lockscreenAlpha,
                        lockscreenToAodTransitionViewModel.lockscreenAlpha(viewState),
                        lockscreenToDozingTransitionViewModel.lockscreenAlpha,
                        lockscreenToDreamingTransitionViewModel.lockscreenAlpha,
                        lockscreenToGlanceableHubTransitionViewModel.keyguardAlpha,
                        lockscreenToGoneTransitionViewModel.lockscreenAlpha(viewState),
                        lockscreenToOccludedTransitionViewModel.lockscreenAlpha,
                        lockscreenToPrimaryBouncerTransitionViewModel.lockscreenAlpha,
                        occludedToAodTransitionViewModel.lockscreenAlpha,
                        occludedToDozingTransitionViewModel.lockscreenAlpha,
                        occludedToLockscreenTransitionViewModel.lockscreenAlpha,
                        primaryBouncerToAodTransitionViewModel.lockscreenAlpha,
                        primaryBouncerToGoneTransitionViewModel.lockscreenAlpha,
                        primaryBouncerToLockscreenTransitionViewModel.lockscreenAlpha,
                    )
                    .onStart { emit(1f) }
            ) { hideKeyguard, alpha ->
                if (hideKeyguard) {
                    0f
                } else {
                    alpha
                }
            }
            .distinctUntilChanged()
    }

    /** Specific alpha value for elements visible during [KeyguardState.LOCKSCREEN] */
    @Deprecated("only used for legacy status view")
    fun lockscreenStateAlpha(viewState: ViewStateAccessor): Flow<Float> {
        return aodToLockscreenTransitionViewModel.lockscreenAlpha(viewState)
    }

    /** For elements that appear and move during the animation -> AOD */
    val burnInLayerAlpha: Flow<Float> = aodAlphaViewModel.alpha

    val translationY: Flow<Float> = burnInModel.map { it.translationY.toFloat() }

    val translationX: Flow<StateToValue> =
        merge(
            burnInModel.map { StateToValue(to = AOD, value = it.translationX.toFloat()) },
            lockscreenToGlanceableHubTransitionViewModel.keyguardTranslationX,
            glanceableHubToLockscreenTransitionViewModel.keyguardTranslationX,
        )

    fun updateBurnInParams(params: BurnInParameters) {
        burnInJob?.cancel()

        burnInJob =
            scope.launch("$TAG#aodBurnInViewModel") {
                aodBurnInViewModel.movement(params).collect { burnInModel.value = it }
            }
    }

    val scale: Flow<BurnInScaleViewModel> =
        burnInModel.map {
            BurnInScaleViewModel(
                scale = it.scale,
                scaleClockOnly = it.scaleClockOnly,
            )
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

    companion object {
        private const val TAG = "KeyguardRootViewModel"
    }
}
