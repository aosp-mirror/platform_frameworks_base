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

package com.android.systemui.keyguard.ui.viewmodel

import android.animation.FloatEvaluator
import android.animation.IntEvaluator
import com.android.keyguard.KeyguardViewController
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryInteractor
import com.android.systemui.deviceentry.domain.interactor.DeviceEntrySourceInteractor
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryUdfpsInteractor
import com.android.systemui.keyguard.domain.interactor.BurnInInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.ui.transitions.DeviceEntryIconTransition
import com.android.systemui.keyguard.ui.view.DeviceEntryIconView
import com.android.systemui.scene.shared.flag.SceneContainerFlags
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.util.kotlin.sample
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart

/** Models the UI state for the containing device entry icon & long-press handling view. */
@ExperimentalCoroutinesApi
class DeviceEntryIconViewModel
@Inject
constructor(
    transitions: Set<@JvmSuppressWildcards DeviceEntryIconTransition>,
    burnInInteractor: BurnInInteractor,
    shadeInteractor: ShadeInteractor,
    deviceEntryUdfpsInteractor: DeviceEntryUdfpsInteractor,
    transitionInteractor: KeyguardTransitionInteractor,
    val keyguardInteractor: KeyguardInteractor,
    val viewModel: AodToLockscreenTransitionViewModel,
    private val sceneContainerFlags: SceneContainerFlags,
    private val keyguardViewController: Lazy<KeyguardViewController>,
    private val deviceEntryInteractor: DeviceEntryInteractor,
    private val deviceEntrySourceInteractor: DeviceEntrySourceInteractor,
) {
    private val intEvaluator = IntEvaluator()
    private val floatEvaluator = FloatEvaluator()
    private val showingAlternateBouncer: Flow<Boolean> =
        transitionInteractor.startedKeyguardState.map { keyguardState ->
            keyguardState == KeyguardState.ALTERNATE_BOUNCER
        }
    private val qsProgress: Flow<Float> = shadeInteractor.qsExpansion.onStart { emit(0f) }
    private val shadeExpansion: Flow<Float> = shadeInteractor.shadeExpansion.onStart { emit(0f) }
    private val transitionAlpha: Flow<Float> =
        transitions.map { it.deviceEntryParentViewAlpha }.merge()
    private val alphaMultiplierFromShadeExpansion: Flow<Float> =
        combine(
            showingAlternateBouncer,
            shadeExpansion,
            qsProgress,
        ) { showingAltBouncer, shadeExpansion, qsProgress ->
            val interpolatedQsProgress = (qsProgress * 2).coerceIn(0f, 1f)
            if (showingAltBouncer) {
                1f
            } else {
                (1f - shadeExpansion) * (1f - interpolatedQsProgress)
            }
        }
    // Burn-in offsets in AOD
    private val nonAnimatedBurnInOffsets: Flow<BurnInOffsets> =
        combine(
            burnInInteractor.deviceEntryIconXOffset,
            burnInInteractor.deviceEntryIconYOffset,
            burnInInteractor.udfpsProgress
        ) { fullyDozingBurnInX, fullyDozingBurnInY, fullyDozingBurnInProgress ->
            BurnInOffsets(
                fullyDozingBurnInX,
                fullyDozingBurnInY,
                fullyDozingBurnInProgress,
            )
        }

    private val dozeAmount: Flow<Float> =
        combine(
            transitionInteractor.startedKeyguardTransitionStep,
            merge(
                transitionInteractor.transitionStepsFromState(KeyguardState.AOD).map {
                    1f - it.value
                },
                transitionInteractor.transitionStepsToState(KeyguardState.AOD).map { it.value }
            ),
        ) { startedKeyguardTransitionStep, aodTransitionAmount ->
            if (
                startedKeyguardTransitionStep.to == KeyguardState.AOD ||
                    startedKeyguardTransitionStep.from == KeyguardState.AOD
            ) {
                aodTransitionAmount
            } else {
                // in case a new transition (ie: to occluded) cancels a transition to or from
                // aod, then we want to make sure the doze amount is still updated to 0
                0f
            }
        }
    // Burn-in offsets that animate based on the transition amount to AOD
    private val animatedBurnInOffsets: Flow<BurnInOffsets> =
        combine(nonAnimatedBurnInOffsets, dozeAmount) { burnInOffsets, dozeAmount ->
            BurnInOffsets(
                intEvaluator.evaluate(dozeAmount, 0, burnInOffsets.x),
                intEvaluator.evaluate(dozeAmount, 0, burnInOffsets.y),
                floatEvaluator.evaluate(dozeAmount, 0, burnInOffsets.progress)
            )
        }

    val deviceEntryViewAlpha: Flow<Float> =
        combine(
            transitionAlpha,
            alphaMultiplierFromShadeExpansion,
        ) { alpha, alphaMultiplier ->
            alpha * alphaMultiplier
        }
    val useBackgroundProtection: Flow<Boolean> = deviceEntryUdfpsInteractor.isUdfpsSupported
    val burnInOffsets: Flow<BurnInOffsets> =
        deviceEntryUdfpsInteractor.isUdfpsEnrolledAndEnabled.flatMapLatest { udfpsEnrolled ->
            if (udfpsEnrolled) {
                combine(
                    transitionInteractor.startedKeyguardTransitionStep.sample(
                        shadeInteractor.isAnyFullyExpanded,
                        ::Pair
                    ),
                    animatedBurnInOffsets,
                    nonAnimatedBurnInOffsets,
                ) {
                    (startedTransitionStep, shadeExpanded),
                    animatedBurnInOffsets,
                    nonAnimatedBurnInOffsets ->
                    if (startedTransitionStep.to == KeyguardState.AOD) {
                        when (startedTransitionStep.from) {
                            KeyguardState.ALTERNATE_BOUNCER -> animatedBurnInOffsets
                            KeyguardState.LOCKSCREEN ->
                                if (shadeExpanded) {
                                    nonAnimatedBurnInOffsets
                                } else {
                                    animatedBurnInOffsets
                                }
                            else -> nonAnimatedBurnInOffsets
                        }
                    } else if (startedTransitionStep.from == KeyguardState.AOD) {
                        when (startedTransitionStep.to) {
                            KeyguardState.LOCKSCREEN -> animatedBurnInOffsets
                            else -> BurnInOffsets(x = 0, y = 0, progress = 0f)
                        }
                    } else {
                        BurnInOffsets(x = 0, y = 0, progress = 0f)
                    }
                }
            } else {
                // If UDFPS isn't enrolled, we don't show any UI on AOD so there's no need
                // to use burn in offsets at all
                flowOf(BurnInOffsets(x = 0, y = 0, progress = 0f))
            }
        }

    private val isUnlocked: Flow<Boolean> =
        deviceEntryInteractor.isUnlocked.flatMapLatest { isUnlocked ->
            if (!isUnlocked) {
                flowOf(false)
            } else {
                flow {
                    // delay in case device ends up transitioning away from the lock screen;
                    // we don't want to animate to the unlocked icon and just let the
                    // icon fade with the transition to GONE
                    delay(UNLOCKED_DELAY_MS)
                    emit(true)
                }
            }
        }

    val iconType: Flow<DeviceEntryIconView.IconType> =
        combine(
            deviceEntryUdfpsInteractor.isListeningForUdfps,
            keyguardInteractor.isKeyguardDismissible,
        ) { isListeningForUdfps, isUnlocked ->
            if (isListeningForUdfps) {
                DeviceEntryIconView.IconType.FINGERPRINT
            } else if (isUnlocked) {
                DeviceEntryIconView.IconType.UNLOCK
            } else {
                DeviceEntryIconView.IconType.LOCK
            }
        }
    val isVisible: Flow<Boolean> = deviceEntryViewAlpha.map { it > 0f }.distinctUntilChanged()
    val isLongPressEnabled: Flow<Boolean> =
        combine(
            iconType,
            deviceEntryUdfpsInteractor.isUdfpsSupported,
        ) { deviceEntryStatus, isUdfps ->
            when (deviceEntryStatus) {
                DeviceEntryIconView.IconType.LOCK -> isUdfps
                DeviceEntryIconView.IconType.UNLOCK -> true
                DeviceEntryIconView.IconType.FINGERPRINT -> false
            }
        }

    val accessibilityDelegateHint: Flow<DeviceEntryIconView.AccessibilityHintType> =
        combine(iconType, isLongPressEnabled) { deviceEntryStatus, longPressEnabled ->
            if (longPressEnabled) {
                deviceEntryStatus.toAccessibilityHintType()
            } else {
                DeviceEntryIconView.AccessibilityHintType.NONE
            }
        }

    suspend fun onLongPress() {
        if (sceneContainerFlags.isEnabled()) {
            deviceEntryInteractor.attemptDeviceEntry()
        } else {
            keyguardViewController.get().showPrimaryBouncer(/* scrim */ true)
        }
        deviceEntrySourceInteractor.attemptEnterDeviceFromDeviceEntryIcon()
    }

    private fun DeviceEntryIconView.IconType.toAccessibilityHintType():
        DeviceEntryIconView.AccessibilityHintType {
        return when (this) {
            DeviceEntryIconView.IconType.LOCK ->
                DeviceEntryIconView.AccessibilityHintType.AUTHENTICATE
            DeviceEntryIconView.IconType.UNLOCK -> DeviceEntryIconView.AccessibilityHintType.ENTER
            DeviceEntryIconView.IconType.FINGERPRINT ->
                DeviceEntryIconView.AccessibilityHintType.NONE
        }
    }

    companion object {
        const val UNLOCKED_DELAY_MS = 50L
    }
}

data class BurnInOffsets(
    val x: Int, // current x burn in offset based on the aodTransitionAmount
    val y: Int, // current y burn in offset based on the aodTransitionAmount
    val progress: Float, // current progress based on the aodTransitionAmount
)
