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

package com.android.systemui.keyguard.ui.viewmodel

import android.util.Log
import android.util.MathUtils
import com.android.app.animation.Interpolators
import com.android.systemui.common.ui.domain.interactor.ConfigurationInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.MigrateClocksToBlueprint
import com.android.systemui.keyguard.domain.interactor.BurnInInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.BurnInModel
import com.android.systemui.keyguard.shared.model.ClockSize
import com.android.systemui.keyguard.ui.StateToValue
import com.android.systemui.res.R
import javax.inject.Inject
import kotlin.math.max
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * Models UI state for elements that need to apply anti-burn-in tactics when showing in AOD
 * (always-on display).
 */
@SysUISingleton
class AodBurnInViewModel
@Inject
constructor(
    private val burnInInteractor: BurnInInteractor,
    private val configurationInteractor: ConfigurationInteractor,
    private val keyguardInteractor: KeyguardInteractor,
    private val keyguardTransitionInteractor: KeyguardTransitionInteractor,
    private val goneToAodTransitionViewModel: GoneToAodTransitionViewModel,
    private val aodToLockscreenTransitionViewModel: AodToLockscreenTransitionViewModel,
    private val occludedToLockscreenTransitionViewModel: OccludedToLockscreenTransitionViewModel,
    private val keyguardClockViewModel: KeyguardClockViewModel,
) {
    private val TAG = "AodBurnInViewModel"

    /** All burn-in movement: x,y,scale, to shift items and prevent burn-in */
    fun movement(
        burnInParams: BurnInParameters,
    ): Flow<BurnInModel> {
        val params =
            if (burnInParams.minViewY < burnInParams.topInset) {
                // minViewY should never be below the inset. Correct it if needed
                Log.w(TAG, "minViewY is below topInset: $burnInParams")
                burnInParams.copy(minViewY = burnInParams.topInset)
            } else {
                burnInParams
            }
        return configurationInteractor
            .dimensionPixelSize(R.dimen.keyguard_enter_from_top_translation_y)
            .flatMapLatest { enterFromTopAmount ->
                combine(
                    keyguardInteractor.keyguardTranslationY.onStart { emit(0f) },
                    burnIn(params).onStart { emit(BurnInModel()) },
                    goneToAodTransitionViewModel
                        .enterFromTopTranslationY(enterFromTopAmount)
                        .onStart { emit(StateToValue()) },
                    occludedToLockscreenTransitionViewModel.lockscreenTranslationY.onStart {
                        emit(0f)
                    },
                    aodToLockscreenTransitionViewModel.translationY(params.translationY).onStart {
                        emit(StateToValue())
                    },
                ) {
                    keyguardTranslationY,
                    burnInModel,
                    goneToAod,
                    occludedToLockscreen,
                    aodToLockscreen ->
                    val translationY =
                        if (aodToLockscreen.transitionState.isTransitioning()) {
                            aodToLockscreen.value ?: 0f
                        } else if (goneToAod.transitionState.isTransitioning()) {
                            (goneToAod.value ?: 0f) + burnInModel.translationY
                        } else {
                            burnInModel.translationY + occludedToLockscreen + keyguardTranslationY
                        }
                    burnInModel.copy(translationY = translationY.toInt())
                }
            }
            .distinctUntilChanged()
    }

    private fun burnIn(
        params: BurnInParameters,
    ): Flow<BurnInModel> {
        return combine(
            keyguardTransitionInteractor.dozeAmountTransition.map {
                Interpolators.FAST_OUT_SLOW_IN.getInterpolation(it.value)
            },
            burnInInteractor.burnIn(
                xDimenResourceId = R.dimen.burn_in_prevention_offset_x,
                yDimenResourceId = R.dimen.burn_in_prevention_offset_y
            ),
        ) { interpolated, burnIn ->
            val useAltAod =
                keyguardClockViewModel.currentClock.value
                    ?.config
                    ?.useAlternateSmartspaceAODTransition == true
            // Only scale large non-weather clocks
            // elements in large weather clock will translate the same as smartspace
            val useScaleOnly =
                (!useAltAod) && keyguardClockViewModel.clockSize.value == ClockSize.LARGE

            val burnInY = MathUtils.lerp(0, burnIn.translationY, interpolated).toInt()
            val translationY =
                if (MigrateClocksToBlueprint.isEnabled) {
                    max(params.topInset - params.minViewY, burnInY)
                } else {
                    max(params.topInset, params.minViewY + burnInY) - params.minViewY
                }
            BurnInModel(
                translationX = MathUtils.lerp(0, burnIn.translationX, interpolated).toInt(),
                translationY = translationY,
                scale = MathUtils.lerp(burnIn.scale, 1f, 1f - interpolated),
                scaleClockOnly = useScaleOnly
            )
        }
    }
}

/** UI-sourced parameters to pass into the various methods of [AodBurnInViewModel]. */
data class BurnInParameters(
    /** System insets that keyguard needs to stay out of */
    val topInset: Int = 0,
    /** The min y-value of the visible elements on lockscreen */
    val minViewY: Int = Int.MAX_VALUE,
    /** The current y translation of the view */
    val translationY: () -> Float? = { null }
)

/**
 * Models UI state of the scaling to apply to elements that need to be scaled for anti-burn-in
 * purposes.
 */
data class BurnInScaleViewModel(
    val scale: Float = 1f,
    /** Whether the scale only applies to clock UI elements. */
    val scaleClockOnly: Boolean = false,
)
