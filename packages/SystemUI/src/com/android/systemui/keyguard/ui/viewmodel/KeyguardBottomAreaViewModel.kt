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

import androidx.annotation.VisibleForTesting
import com.android.systemui.doze.util.BurnInHelperWrapper
import com.android.systemui.keyguard.domain.interactor.KeyguardBottomAreaInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardQuickAffordanceInteractor
import com.android.systemui.keyguard.domain.model.KeyguardQuickAffordanceModel
import com.android.systemui.keyguard.domain.model.KeyguardQuickAffordancePosition
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** View-model for the keyguard bottom area view */
class KeyguardBottomAreaViewModel
@Inject
constructor(
    private val keyguardInteractor: KeyguardInteractor,
    private val quickAffordanceInteractor: KeyguardQuickAffordanceInteractor,
    private val bottomAreaInteractor: KeyguardBottomAreaInteractor,
    private val burnInHelperWrapper: BurnInHelperWrapper,
) {
    /**
     * Whether quick affordances are "opaque enough" to be considered visible to and interactive by
     * the user. If they are not interactive, user input should not be allowed on them.
     *
     * Note that there is a margin of error, where we allow very, very slightly transparent views to
     * be considered "fully opaque" for the purpose of being interactive. This is to accommodate the
     * error margin of floating point arithmetic.
     *
     * A view that is visible but with an alpha of less than our threshold either means it's not
     * fully done fading in or is fading/faded out. Either way, it should not be
     * interactive/clickable unless "fully opaque" to avoid issues like in b/241830987.
     */
    private val areQuickAffordancesFullyOpaque: Flow<Boolean> =
        bottomAreaInteractor.alpha
            .map { alpha -> alpha >= AFFORDANCE_FULLY_OPAQUE_ALPHA_THRESHOLD }
            .distinctUntilChanged()

    /** An observable for the view-model of the "start button" quick affordance. */
    val startButton: Flow<KeyguardQuickAffordanceViewModel> =
        button(KeyguardQuickAffordancePosition.BOTTOM_START)
    /** An observable for the view-model of the "end button" quick affordance. */
    val endButton: Flow<KeyguardQuickAffordanceViewModel> =
        button(KeyguardQuickAffordancePosition.BOTTOM_END)
    /** An observable for whether the overlay container should be visible. */
    val isOverlayContainerVisible: Flow<Boolean> =
        keyguardInteractor.isDozing.map { !it }.distinctUntilChanged()
    /** An observable for the alpha level for the entire bottom area. */
    val alpha: Flow<Float> = bottomAreaInteractor.alpha.distinctUntilChanged()
    /** An observable for whether the indication area should be padded. */
    val isIndicationAreaPadded: Flow<Boolean> =
        combine(startButton, endButton) { startButtonModel, endButtonModel ->
                startButtonModel.isVisible || endButtonModel.isVisible
            }
            .distinctUntilChanged()
    /** An observable for the x-offset by which the indication area should be translated. */
    val indicationAreaTranslationX: Flow<Float> =
        bottomAreaInteractor.clockPosition.map { it.x.toFloat() }.distinctUntilChanged()

    /** Returns an observable for the y-offset by which the indication area should be translated. */
    fun indicationAreaTranslationY(defaultBurnInOffset: Int): Flow<Float> {
        return keyguardInteractor.dozeAmount
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
        return combine(
                quickAffordanceInteractor.quickAffordance(position),
                bottomAreaInteractor.animateDozingTransitions.distinctUntilChanged(),
                areQuickAffordancesFullyOpaque,
            ) { model, animateReveal, isFullyOpaque ->
                model.toViewModel(
                    animateReveal = animateReveal,
                    isClickable = isFullyOpaque,
                )
            }
            .distinctUntilChanged()
    }

    private fun KeyguardQuickAffordanceModel.toViewModel(
        animateReveal: Boolean,
        isClickable: Boolean,
    ): KeyguardQuickAffordanceViewModel {
        return when (this) {
            is KeyguardQuickAffordanceModel.Visible ->
                KeyguardQuickAffordanceViewModel(
                    configKey = configKey,
                    isVisible = true,
                    animateReveal = animateReveal,
                    icon = icon,
                    contentDescriptionResourceId = contentDescriptionResourceId,
                    onClicked = { parameters ->
                        quickAffordanceInteractor.onQuickAffordanceClicked(
                            configKey = parameters.configKey,
                            animationController = parameters.animationController,
                        )
                    },
                    isClickable = isClickable,
                )
            is KeyguardQuickAffordanceModel.Hidden -> KeyguardQuickAffordanceViewModel()
        }
    }

    companion object {
        // We select a value that's less than 1.0 because we want floating point math precision to
        // not be a factor in determining whether the affordance UI is fully opaque. The number we
        // choose needs to be close enough 1.0 such that the user can't easily tell the difference
        // between the UI with an alpha at the threshold and when the alpha is 1.0. At the same
        // time, we don't want the number to be too close to 1.0 such that there is a chance that we
        // never treat the affordance UI as "fully opaque" as that would risk making it forever not
        // clickable.
        @VisibleForTesting const val AFFORDANCE_FULLY_OPAQUE_ALPHA_THRESHOLD = 0.95f
    }
}
