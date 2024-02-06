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

import android.util.MathUtils
import com.android.app.animation.Interpolators
import com.android.keyguard.KeyguardClockSwitch
import com.android.systemui.Flags
import com.android.systemui.common.ui.domain.interactor.ConfigurationInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.domain.interactor.BurnInInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.BurnInModel
import com.android.systemui.keyguard.shared.model.KeyguardState.ALTERNATE_BOUNCER
import com.android.systemui.keyguard.shared.model.KeyguardState.AOD
import com.android.systemui.keyguard.shared.model.KeyguardState.GONE
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionState.RUNNING
import com.android.systemui.keyguard.shared.model.TransitionState.STARTED
import com.android.systemui.keyguard.ui.StateToValue
import com.android.systemui.plugins.clocks.ClockController
import com.android.systemui.res.R
import javax.inject.Inject
import javax.inject.Provider
import kotlin.math.max
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
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
    /** Alpha for elements that appear and move during the animation -> AOD */
    val alpha: Flow<Float> = goneToAodTransitionViewModel.enterFromTopAnimationAlpha

    /** Horizontal translation for elements that need to apply anti-burn-in tactics. */
    fun translationX(
        params: BurnInParameters,
    ): Flow<Float> {
        return burnIn(params).map { it.translationX.toFloat() }
    }

    /** Vertical translation for elements that need to apply anti-burn-in tactics. */
    fun translationY(
        params: BurnInParameters,
    ): Flow<Float> {
        return configurationInteractor
            .dimensionPixelSize(R.dimen.keyguard_enter_from_top_translation_y)
            .flatMapLatest { enterFromTopAmount ->
                combine(
                    keyguardInteractor.keyguardTranslationY.onStart { emit(0f) },
                    burnIn(params).map { it.translationY.toFloat() }.onStart { emit(0f) },
                    goneToAodTransitionViewModel
                        .enterFromTopTranslationY(enterFromTopAmount)
                        .onStart { emit(StateToValue()) },
                    occludedToLockscreenTransitionViewModel.lockscreenTranslationY.onStart {
                        emit(0f)
                    },
                    aodToLockscreenTransitionViewModel.translationY(params.translationY).onStart {
                        emit(StateToValue())
                    },
                ) { keyguardTranslationY, burnInY, goneToAod, occludedToLockscreen, aodToLockscreen
                    ->
                    if (isInTransition(aodToLockscreen.transitionState)) {
                        aodToLockscreen.value ?: 0f
                    } else if (isInTransition(goneToAod.transitionState)) {
                        (goneToAod.value ?: 0f) + burnInY
                    } else {
                        burnInY + occludedToLockscreen + keyguardTranslationY
                    }
                }
            }
            .distinctUntilChanged()
    }

    /** Scale for elements that need to apply anti-burn-in tactics. */
    fun scale(
        params: BurnInParameters,
    ): Flow<BurnInScaleViewModel> {
        return burnIn(params).map {
            BurnInScaleViewModel(
                scale = it.scale,
                scaleClockOnly = it.scaleClockOnly,
            )
        }
    }

    private fun isInTransition(state: TransitionState): Boolean {
        return state == STARTED || state == RUNNING
    }

    private fun burnIn(
        params: BurnInParameters,
    ): Flow<BurnInModel> {
        return combine(
            merge(
                    keyguardTransitionInteractor.transition(GONE, AOD).map { it.value },
                    keyguardTransitionInteractor.transition(ALTERNATE_BOUNCER, AOD).map {
                        it.value
                    },
                    keyguardTransitionInteractor.dozeAmountTransition.map { it.value },
                )
                .map { dozeAmount -> Interpolators.FAST_OUT_SLOW_IN.getInterpolation(dozeAmount) },
            burnInInteractor.keyguardBurnIn,
        ) { interpolated, burnIn ->
            val useScaleOnly =
                (clockController(params.clockControllerProvider)
                    ?.get()
                    ?.config
                    ?.useAlternateSmartspaceAODTransition
                    ?: false) && keyguardClockViewModel.clockSize.value == KeyguardClockSwitch.LARGE

            if (useScaleOnly) {
                BurnInModel(
                    translationX = 0,
                    translationY = 0,
                    scale = MathUtils.lerp(burnIn.scale, 1f, 1f - interpolated),
                )
            } else {
                // Ensure the desired translation doesn't encroach on the top inset
                val burnInY = MathUtils.lerp(0, burnIn.translationY, interpolated).toInt()
                val translationY =
                    if (Flags.migrateClocksToBlueprint()) {
                        burnInY
                    } else {
                        max(params.topInset, params.statusViewTop + burnInY) - params.statusViewTop
                    }

                BurnInModel(
                    translationX = MathUtils.lerp(0, burnIn.translationX, interpolated).toInt(),
                    translationY = translationY,
                    scale =
                        MathUtils.lerp(
                            /* start= */ burnIn.scale,
                            /* stop= */ 1f,
                            /* amount= */ 1f - interpolated,
                        ),
                    scaleClockOnly = true,
                )
            }
        }
    }

    private fun clockController(
        provider: Provider<ClockController>?,
    ): Provider<ClockController>? {
        return if (Flags.migrateClocksToBlueprint()) {
            Provider { keyguardClockViewModel.clock }
        } else {
            provider
        }
    }
}

/** UI-sourced parameters to pass into the various methods of [AodBurnInViewModel]. */
data class BurnInParameters(
    val clockControllerProvider: Provider<ClockController>? = null,
    /** System insets that keyguard needs to stay out of */
    val topInset: Int = 0,
    /** Status view top, without translation added in */
    val statusViewTop: Int = 0,
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
