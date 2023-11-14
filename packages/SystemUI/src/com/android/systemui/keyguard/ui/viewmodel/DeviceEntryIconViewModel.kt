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
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryHapticsInteractor
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryInteractor
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryUdfpsInteractor
import com.android.systemui.keyguard.domain.interactor.BurnInInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.ui.transitions.DeviceEntryIconTransition
import com.android.systemui.keyguard.ui.view.DeviceEntryIconView
import com.android.systemui.scene.shared.flag.SceneContainerFlags
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
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
    val shadeDependentFlows: ShadeDependentFlows,
    private val sceneContainerFlags: SceneContainerFlags,
    private val keyguardViewController: Lazy<KeyguardViewController>,
    private val deviceEntryHapticsInteractor: DeviceEntryHapticsInteractor,
    udfpsInteractor: DeviceEntryUdfpsInteractor,
    private val deviceEntryInteractor: DeviceEntryInteractor,
) {
    private val intEvaluator = IntEvaluator()
    private val floatEvaluator = FloatEvaluator()
    private val toAodFromState: Flow<KeyguardState> =
        transitionInteractor.transitionStepsToState(KeyguardState.AOD).map { it.from }
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
    // Burn-in offsets that animate based on the transition amount to AOD
    private val animatedBurnInOffsets: Flow<BurnInOffsets> =
        combine(
            nonAnimatedBurnInOffsets,
            transitionInteractor.transitionStepsToState(KeyguardState.AOD)
        ) { burnInOffsets, transitionStepsToAod ->
            val dozeAmount = transitionStepsToAod.value
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
                toAodFromState.flatMapLatest { fromState ->
                    when (fromState) {
                        KeyguardState.AOD,
                        KeyguardState.GONE,
                        KeyguardState.OCCLUDED,
                        KeyguardState.DREAMING_LOCKSCREEN_HOSTED,
                        KeyguardState.OFF,
                        KeyguardState.DOZING,
                        KeyguardState.DREAMING,
                        KeyguardState.PRIMARY_BOUNCER -> nonAnimatedBurnInOffsets
                        KeyguardState.ALTERNATE_BOUNCER -> animatedBurnInOffsets
                        KeyguardState.LOCKSCREEN ->
                            shadeDependentFlows.transitionFlow(
                                flowWhenShadeIsExpanded = nonAnimatedBurnInOffsets,
                                flowWhenShadeIsNotExpanded = animatedBurnInOffsets,
                            )
                    }
                }
            } else {
                // If UDFPS isn't enrolled, we don't show any UI on AOD so there's no need
                // to use burn in offsets at all
                flowOf(BurnInOffsets(x = 0, y = 0, progress = 0f))
            }
        }
    val iconType: Flow<DeviceEntryIconView.IconType> =
        combine(
            udfpsInteractor.isListeningForUdfps,
            deviceEntryInteractor.isUnlocked,
        ) { isListeningForUdfps, isUnlocked ->
            if (isUnlocked) {
                DeviceEntryIconView.IconType.UNLOCK
            } else {
                if (isListeningForUdfps) {
                    DeviceEntryIconView.IconType.FINGERPRINT
                } else {
                    DeviceEntryIconView.IconType.LOCK
                }
            }
        }
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

    fun onLongPress() {
        deviceEntryHapticsInteractor.vibrateSuccess()

        // TODO (b/309804148): play auth ripple via an interactor

        if (sceneContainerFlags.isEnabled()) {
            deviceEntryInteractor.attemptDeviceEntry()
        } else {
            keyguardViewController.get().showPrimaryBouncer(/* scrim */ true)
        }
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
}

data class BurnInOffsets(
    val x: Int, // current x burn in offset based on the aodTransitionAmount
    val y: Int, // current y burn in offset based on the aodTransitionAmount
    val progress: Float, // current progress based on the aodTransitionAmount
)
