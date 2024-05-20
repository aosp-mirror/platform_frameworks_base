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
import com.android.systemui.accessibility.domain.interactor.AccessibilityInteractor
import com.android.systemui.biometrics.shared.model.SensorLocation
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryInteractor
import com.android.systemui.deviceentry.domain.interactor.DeviceEntrySourceInteractor
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryUdfpsInteractor
import com.android.systemui.keyguard.domain.interactor.BurnInInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.ui.transitions.DeviceEntryIconTransition
import com.android.systemui.keyguard.ui.view.DeviceEntryIconView
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.util.kotlin.sample
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn

/** Models the UI state for the containing device entry icon & long-press handling view. */
@ExperimentalCoroutinesApi
@SysUISingleton
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
    private val keyguardViewController: Lazy<KeyguardViewController>,
    private val deviceEntryInteractor: DeviceEntryInteractor,
    private val deviceEntrySourceInteractor: DeviceEntrySourceInteractor,
    private val accessibilityInteractor: AccessibilityInteractor,
    @Application private val scope: CoroutineScope,
) {
    val isUdfpsSupported: StateFlow<Boolean> = deviceEntryUdfpsInteractor.isUdfpsSupported
    val udfpsLocation: StateFlow<SensorLocation?> =
        deviceEntryUdfpsInteractor.udfpsLocation.stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = null,
        )
    private val intEvaluator = IntEvaluator()
    private val floatEvaluator = FloatEvaluator()
    private val showingAlternateBouncer: Flow<Boolean> =
        transitionInteractor.startedKeyguardState.map { keyguardState ->
            keyguardState == KeyguardState.ALTERNATE_BOUNCER
        }
    private val qsProgress: Flow<Float> = shadeInteractor.qsExpansion.onStart { emit(0f) }
    private val shadeExpansion: Flow<Float> = shadeInteractor.shadeExpansion.onStart { emit(0f) }
    private val transitionAlpha: Flow<Float> =
        transitions
            .map { it.deviceEntryParentViewAlpha }
            .merge()
            .shareIn(scope, SharingStarted.WhileSubscribed())
            .onStart { emit(initialAlphaFromKeyguardState(transitionInteractor.getCurrentState())) }
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
            .onStart { emit(1f) }
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

    private val dozeAmount: Flow<Float> = transitionInteractor.transitionValue(KeyguardState.AOD)
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
            .stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = 0f,
            )

    private fun initialAlphaFromKeyguardState(keyguardState: KeyguardState): Float {
        return when (keyguardState) {
            KeyguardState.OFF,
            KeyguardState.PRIMARY_BOUNCER,
            KeyguardState.DOZING,
            KeyguardState.DREAMING,
            KeyguardState.GLANCEABLE_HUB,
            KeyguardState.GONE,
            KeyguardState.OCCLUDED,
            KeyguardState.DREAMING_LOCKSCREEN_HOSTED,
            KeyguardState.UNDEFINED, -> 0f
            KeyguardState.AOD,
            KeyguardState.ALTERNATE_BOUNCER,
            KeyguardState.LOCKSCREEN, -> 1f
        }
    }
    val useBackgroundProtection: StateFlow<Boolean> = isUdfpsSupported
    val burnInOffsets: Flow<BurnInOffsets> =
        deviceEntryUdfpsInteractor.isUdfpsEnrolledAndEnabled
            .flatMapLatest { udfpsEnrolled ->
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
            .distinctUntilChanged()

    private val isUnlocked: Flow<Boolean> =
        keyguardInteractor.isKeyguardDismissible.flatMapLatest { isUnlocked ->
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
            isUnlocked,
        ) { isListeningForUdfps, isUnlocked ->
            if (isListeningForUdfps) {
                if (isUnlocked) {
                    // Don't show any UI until isUnlocked=false. This covers the case
                    // when the "Power button instantly locks > 0s" or the device doesn't lock
                    // immediately after a screen time.
                    DeviceEntryIconView.IconType.NONE
                } else {
                    DeviceEntryIconView.IconType.FINGERPRINT
                }
            } else if (isUnlocked) {
                DeviceEntryIconView.IconType.UNLOCK
            } else {
                DeviceEntryIconView.IconType.LOCK
            }
        }
    val isVisible: Flow<Boolean> = deviceEntryViewAlpha.map { it > 0f }.distinctUntilChanged()

    private val isInteractive: Flow<Boolean> =
        combine(
            iconType,
            isUdfpsSupported,
        ) { deviceEntryStatus, isUdfps ->
            when (deviceEntryStatus) {
                DeviceEntryIconView.IconType.LOCK -> isUdfps
                DeviceEntryIconView.IconType.UNLOCK -> true
                DeviceEntryIconView.IconType.FINGERPRINT,
                DeviceEntryIconView.IconType.NONE -> false
            }
        }
    val accessibilityDelegateHint: Flow<DeviceEntryIconView.AccessibilityHintType> =
        accessibilityInteractor.isEnabled.flatMapLatest { touchExplorationEnabled ->
            if (touchExplorationEnabled) {
                combine(iconType, isInteractive) { iconType, isInteractive ->
                    if (isInteractive) {
                        iconType.toAccessibilityHintType()
                    } else {
                        DeviceEntryIconView.AccessibilityHintType.NONE
                    }
                }
            } else {
                flowOf(DeviceEntryIconView.AccessibilityHintType.NONE)
            }
        }

    val isLongPressEnabled: Flow<Boolean> = isInteractive

    suspend fun onUserInteraction() {
        if (SceneContainerFlag.isEnabled) {
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
            DeviceEntryIconView.IconType.FINGERPRINT,
            DeviceEntryIconView.IconType.NONE -> DeviceEntryIconView.AccessibilityHintType.NONE
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
