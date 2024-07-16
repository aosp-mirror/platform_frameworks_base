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

import com.android.systemui.common.ui.domain.interactor.ConfigurationInteractor
import com.android.systemui.communal.domain.interactor.CommunalSceneInteractor
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.doze.util.BurnInHelperWrapper
import com.android.systemui.keyguard.KeyguardBottomAreaRefactor
import com.android.systemui.keyguard.MigrateClocksToBlueprint
import com.android.systemui.keyguard.domain.interactor.BurnInInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardBottomAreaInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.BurnInModel
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.StatusBarState
import com.android.systemui.res.R
import com.android.systemui.util.kotlin.BooleanFlowOperators.anyOf
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** View-model for the keyguard indication area view */
class KeyguardIndicationAreaViewModel
@Inject
constructor(
    private val keyguardInteractor: KeyguardInteractor,
    bottomAreaInteractor: KeyguardBottomAreaInteractor,
    keyguardBottomAreaViewModel: KeyguardBottomAreaViewModel,
    private val burnInHelperWrapper: BurnInHelperWrapper,
    burnInInteractor: BurnInInteractor,
    shortcutsCombinedViewModel: KeyguardQuickAffordancesCombinedViewModel,
    configurationInteractor: ConfigurationInteractor,
    keyguardTransitionInteractor: KeyguardTransitionInteractor,
    communalSceneInteractor: CommunalSceneInteractor,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    @Main private val mainDispatcher: CoroutineDispatcher,
) {

    /** Notifies when a new configuration is set */
    val configurationChange: Flow<Unit> = configurationInteractor.onAnyConfigurationChange

    /** An observable for the alpha level for the entire bottom area. */
    val alpha: Flow<Float> = keyguardBottomAreaViewModel.alpha

    /** An observable for the visibility value for the indication area view. */
    val visible: Flow<Boolean> =
        anyOf(
            keyguardInteractor.statusBarState.map { state -> state == StatusBarState.KEYGUARD },
            communalSceneInteractor.isCommunalVisible
        )

    /** An observable for whether the indication area should be padded. */
    val isIndicationAreaPadded: Flow<Boolean> =
        if (KeyguardBottomAreaRefactor.isEnabled) {
            combine(shortcutsCombinedViewModel.startButton, shortcutsCombinedViewModel.endButton) {
                    startButtonModel,
                    endButtonModel ->
                    startButtonModel.isVisible || endButtonModel.isVisible
                }
                .distinctUntilChanged()
        } else {
            combine(
                    keyguardBottomAreaViewModel.startButton,
                    keyguardBottomAreaViewModel.endButton
                ) { startButtonModel, endButtonModel ->
                    startButtonModel.isVisible || endButtonModel.isVisible
                }
                .distinctUntilChanged()
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val burnIn: Flow<BurnInModel> =
        combine(
                burnInInteractor.burnIn(
                    xDimenResourceId = R.dimen.burn_in_prevention_offset_x,
                    yDimenResourceId = R.dimen.default_burn_in_prevention_offset,
                ),
                keyguardTransitionInteractor.transitionValue(KeyguardState.AOD),
            ) { burnIn, aodTransitionValue ->
                BurnInModel(
                    (burnIn.translationX * aodTransitionValue).toInt(),
                    (burnIn.translationY * aodTransitionValue).toInt(),
                    burnIn.scale,
                    burnIn.scaleClockOnly,
                )
            }
            .distinctUntilChanged()
            .flowOn(backgroundDispatcher)

    /** An observable for the x-offset by which the indication area should be translated. */
    val indicationAreaTranslationX: Flow<Float> =
        if (MigrateClocksToBlueprint.isEnabled || KeyguardBottomAreaRefactor.isEnabled) {
            burnIn.map { it.translationX.toFloat() }.flowOn(mainDispatcher)
        } else {
            bottomAreaInteractor.clockPosition.map { it.x.toFloat() }.distinctUntilChanged()
        }

    /** Returns an observable for the y-offset by which the indication area should be translated. */
    fun indicationAreaTranslationY(defaultBurnInOffset: Int): Flow<Float> {
        return if (MigrateClocksToBlueprint.isEnabled) {
            burnIn.map { it.translationY.toFloat() }.flowOn(mainDispatcher)
        } else {
            keyguardInteractor.dozeAmount
                .map { dozeAmount ->
                    dozeAmount *
                        (burnInHelperWrapper.burnInOffset(
                            /* amplitude = */ defaultBurnInOffset * 2,
                            /* xAxis= */ false,
                        ) - defaultBurnInOffset)
                }
                .distinctUntilChanged()
        }
    }
}
