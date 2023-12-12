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
import com.android.app.animation.Interpolators
import com.android.keyguard.KeyguardClockSwitch.LARGE
import com.android.systemui.Flags.migrateClocksToBlueprint
import com.android.systemui.Flags.newAodTransition
import com.android.systemui.common.shared.model.NotificationContainerBounds
import com.android.systemui.common.ui.domain.interactor.ConfigurationInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryInteractor
import com.android.systemui.flags.FeatureFlagsClassic
import com.android.systemui.keyguard.domain.interactor.BurnInInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.BurnInModel
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.KeyguardState.AOD
import com.android.systemui.keyguard.shared.model.KeyguardState.GONE
import com.android.systemui.keyguard.shared.model.KeyguardState.LOCKSCREEN
import com.android.systemui.plugins.clocks.ClockController
import com.android.systemui.res.R
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
import javax.inject.Provider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart

@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class KeyguardRootViewModel
@Inject
constructor(
    configurationInteractor: ConfigurationInteractor,
    private val deviceEntryInteractor: DeviceEntryInteractor,
    private val dozeParameters: DozeParameters,
    private val keyguardInteractor: KeyguardInteractor,
    private val keyguardTransitionInteractor: KeyguardTransitionInteractor,
    private val notificationsKeyguardInteractor: NotificationsKeyguardInteractor,
    private val burnInInteractor: BurnInInteractor,
    private val keyguardClockViewModel: KeyguardClockViewModel,
    private val goneToAodTransitionViewModel: GoneToAodTransitionViewModel,
    private val aodToLockscreenTransitionViewModel: AodToLockscreenTransitionViewModel,
    private val occludedToLockscreenTransitionViewModel: OccludedToLockscreenTransitionViewModel,
    screenOffAnimationController: ScreenOffAnimationController,
    // TODO(b/310989341): remove after changing migrate_clocks_to_blueprint to aconfig
    private val featureFlags: FeatureFlagsClassic,
) {
    var clockControllerProvider: Provider<ClockController>? = null
        get() {
            if (migrateClocksToBlueprint()) {
                return Provider { keyguardClockViewModel.clock }
            } else {
                return field
            }
        }

    /** System insets that keyguard needs to stay out of */
    var topInset: Int = 0
    /** Status view top, without translation added in */
    var statusViewTop: Int = 0

    val burnInLayerVisibility: Flow<Int> =
        keyguardTransitionInteractor.startedKeyguardState
            .filter { it == AOD || it == LOCKSCREEN }
            .map { VISIBLE }

    val goneToAodTransition = keyguardTransitionInteractor.transition(from = GONE, to = AOD)

    /** Last point that the root view was tapped */
    val lastRootViewTapPosition: Flow<Point?> = keyguardInteractor.lastRootViewTapPosition

    /** the shared notification container bounds *on the lockscreen* */
    val notificationBounds: StateFlow<NotificationContainerBounds> =
        keyguardInteractor.notificationContainerBounds

    /** An observable for the alpha level for the entire keyguard root view. */
    val alpha: Flow<Float> =
        combine(
                keyguardTransitionInteractor.transitionValue(GONE).onStart { emit(0f) },
                merge(
                    keyguardInteractor.keyguardAlpha,
                    occludedToLockscreenTransitionViewModel.lockscreenAlpha,
                )
            ) { transitionToGone, alpha ->
                if (transitionToGone == 1f) {
                    // Ensures content is not visible when in GONE state
                    0f
                } else {
                    alpha
                }
            }
            .distinctUntilChanged()

    private fun burnIn(): Flow<BurnInModel> {
        val dozingAmount: Flow<Float> =
            merge(
                keyguardTransitionInteractor.goneToAodTransition.map { it.value },
                keyguardTransitionInteractor.dozeAmountTransition.map { it.value },
            )

        return combine(dozingAmount, burnInInteractor.keyguardBurnIn) { dozeAmount, burnIn ->
            val interpolation = Interpolators.FAST_OUT_SLOW_IN.getInterpolation(dozeAmount)
            val useScaleOnly =
                (clockControllerProvider?.get()?.config?.useAlternateSmartspaceAODTransition
                    ?: false) && keyguardClockViewModel.clockSize.value == LARGE
            if (useScaleOnly) {
                BurnInModel(
                    translationX = 0,
                    translationY = 0,
                    scale = MathUtils.lerp(burnIn.scale, 1f, 1f - interpolation),
                )
            } else {
                // Ensure the desired translation doesn't encroach on the top inset
                val burnInY = MathUtils.lerp(0, burnIn.translationY, interpolation).toInt()
                val translationY =
                    if (migrateClocksToBlueprint()) {
                        burnInY
                    } else {
                        -(statusViewTop - Math.max(topInset, statusViewTop + burnInY))
                    }
                BurnInModel(
                    translationX = MathUtils.lerp(0, burnIn.translationX, interpolation).toInt(),
                    translationY = translationY,
                    scale = MathUtils.lerp(burnIn.scale, 1f, 1f - interpolation),
                    scaleClockOnly = true,
                )
            }
        }
    }

    /** Specific alpha value for elements visible during [KeyguardState.LOCKSCREEN] */
    val lockscreenStateAlpha: Flow<Float> = aodToLockscreenTransitionViewModel.lockscreenAlpha

    /** For elements that appear and move during the animation -> AOD */
    val burnInLayerAlpha: Flow<Float> = goneToAodTransitionViewModel.enterFromTopAnimationAlpha

    val translationY: Flow<Float> =
        configurationInteractor
            .dimensionPixelSize(R.dimen.keyguard_enter_from_top_translation_y)
            .flatMapLatest { enterFromTopAmount ->
                combine(
                    keyguardInteractor.keyguardTranslationY.onStart { emit(0f) },
                    burnIn().map { it.translationY.toFloat() }.onStart { emit(0f) },
                    goneToAodTransitionViewModel
                        .enterFromTopTranslationY(enterFromTopAmount)
                        .onStart { emit(0f) },
                    occludedToLockscreenTransitionViewModel.lockscreenTranslationY.onStart {
                        emit(0f)
                    },
                ) {
                    keyguardTransitionY,
                    burnInTranslationY,
                    goneToAodTransitionTranslationY,
                    occludedToLockscreenTransitionTranslationY ->
                    // All values need to be combined for a smooth translation
                    keyguardTransitionY +
                        burnInTranslationY +
                        goneToAodTransitionTranslationY +
                        occludedToLockscreenTransitionTranslationY
                }
            }
            .distinctUntilChanged()

    val translationX: Flow<Float> = burnIn().map { it.translationX.toFloat() }

    val scale: Flow<Pair<Float, Boolean>> = burnIn().map { Pair(it.scale, it.scaleClockOnly) }

    /** Is the notification icon container visible? */
    val isNotifIconContainerVisible: Flow<AnimatedValue<Boolean>> =
        combine(
                keyguardTransitionInteractor.finishedKeyguardState.map {
                    KeyguardState.lockscreenVisibleInState(it)
                },
                deviceEntryInteractor.isBypassEnabled,
                areNotifsFullyHiddenAnimated(),
                isPulseExpandingAnimated(),
            ) {
                onKeyguard: Boolean,
                isBypassEnabled: Boolean,
                notifsFullyHidden: AnimatedValue<Boolean>,
                pulseExpanding: AnimatedValue<Boolean>,
                ->
                when {
                    // Hide the AOD icons if we're not in the KEYGUARD state unless the screen off
                    // animation is playing, in which case we want them to be visible if we're
                    // animating in the AOD UI and will be switching to KEYGUARD shortly.
                    !onKeyguard && !screenOffAnimationController.shouldShowAodIconsWhenShade() ->
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
