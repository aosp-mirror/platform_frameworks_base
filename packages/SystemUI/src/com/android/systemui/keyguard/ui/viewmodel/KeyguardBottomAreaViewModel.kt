/*
 * Copyright (C) 2022 The Android Open Source Project
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

import com.android.systemui.doze.util.BurnInHelperWrapper
import com.android.systemui.keyguard.domain.usecase.ObserveAnimateBottomAreaTransitionsUseCase
import com.android.systemui.keyguard.domain.usecase.ObserveBottomAreaAlphaUseCase
import com.android.systemui.keyguard.domain.usecase.ObserveClockPositionUseCase
import com.android.systemui.keyguard.domain.usecase.ObserveDozeAmountUseCase
import com.android.systemui.keyguard.domain.usecase.ObserveIsDozingUseCase
import com.android.systemui.keyguard.domain.usecase.ObserveKeyguardQuickAffordanceUseCase
import com.android.systemui.keyguard.domain.usecase.OnKeyguardQuickAffordanceClickedUseCase
import com.android.systemui.keyguard.shared.model.KeyguardQuickAffordanceModel
import com.android.systemui.keyguard.shared.model.KeyguardQuickAffordancePosition
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** View-model for the keyguard bottom area view */
class KeyguardBottomAreaViewModel
@Inject
constructor(
    private val observeQuickAffordanceUseCase: ObserveKeyguardQuickAffordanceUseCase,
    private val onQuickAffordanceClickedUseCase: OnKeyguardQuickAffordanceClickedUseCase,
    observeBottomAreaAlphaUseCase: ObserveBottomAreaAlphaUseCase,
    observeIsDozingUseCase: ObserveIsDozingUseCase,
    observeAnimateBottomAreaTransitionsUseCase: ObserveAnimateBottomAreaTransitionsUseCase,
    private val observeDozeAmountUseCase: ObserveDozeAmountUseCase,
    observeClockPositionUseCase: ObserveClockPositionUseCase,
    private val burnInHelperWrapper: BurnInHelperWrapper,
) {
    /** An observable for the view-model of the "start button" quick affordance. */
    val startButton: Flow<KeyguardQuickAffordanceViewModel> =
        button(KeyguardQuickAffordancePosition.BOTTOM_START)
    /** An observable for the view-model of the "end button" quick affordance. */
    val endButton: Flow<KeyguardQuickAffordanceViewModel> =
        button(KeyguardQuickAffordancePosition.BOTTOM_END)
    /**
     * An observable for whether the next time a quick action button becomes visible, it should
     * animate.
     */
    val animateButtonReveal: Flow<Boolean> =
        observeAnimateBottomAreaTransitionsUseCase().distinctUntilChanged()
    /** An observable for whether the overlay container should be visible. */
    val isOverlayContainerVisible: Flow<Boolean> =
        observeIsDozingUseCase().map { !it }.distinctUntilChanged()
    /** An observable for the alpha level for the entire bottom area. */
    val alpha: Flow<Float> = observeBottomAreaAlphaUseCase().distinctUntilChanged()
    /** An observable for whether the indication area should be padded. */
    val isIndicationAreaPadded: Flow<Boolean> =
        combine(startButton, endButton) { startButtonModel, endButtonModel ->
                startButtonModel.isVisible || endButtonModel.isVisible
            }
            .distinctUntilChanged()
    /** An observable for the x-offset by which the indication area should be translated. */
    val indicationAreaTranslationX: Flow<Float> =
        observeClockPositionUseCase().map { it.x.toFloat() }.distinctUntilChanged()

    /** Returns an observable for the y-offset by which the indication area should be translated. */
    fun indicationAreaTranslationY(defaultBurnInOffset: Int): Flow<Float> {
        return observeDozeAmountUseCase()
            .map { dozeAmount ->
                dozeAmount *
                    (burnInHelperWrapper.burnInOffset(
                        /* amplitude = */ defaultBurnInOffset * 2,
                        /* xAxis= */ false,
                    ) - defaultBurnInOffset)
            }
            .distinctUntilChanged()
    }

    private fun button(
        position: KeyguardQuickAffordancePosition
    ): Flow<KeyguardQuickAffordanceViewModel> {
        return observeQuickAffordanceUseCase(position)
            .map { model -> model.toViewModel() }
            .distinctUntilChanged()
    }

    private fun KeyguardQuickAffordanceModel.toViewModel(): KeyguardQuickAffordanceViewModel {
        return when (this) {
            is KeyguardQuickAffordanceModel.Visible ->
                KeyguardQuickAffordanceViewModel(
                    configKey = configKey,
                    isVisible = true,
                    icon = icon,
                    contentDescriptionResourceId = contentDescriptionResourceId,
                    onClicked = { parameters ->
                        onQuickAffordanceClickedUseCase(
                            configKey = parameters.configKey,
                            animationController = parameters.animationController,
                        )
                    },
                )
            is KeyguardQuickAffordanceModel.Hidden -> KeyguardQuickAffordanceViewModel()
        }
    }
}
