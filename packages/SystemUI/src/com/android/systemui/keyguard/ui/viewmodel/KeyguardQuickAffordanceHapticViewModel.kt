/*
 * Copyright (C) 2024 The Android Open Source Project
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

import com.android.systemui.keyguard.domain.interactor.KeyguardQuickAffordanceInteractor
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

class KeyguardQuickAffordanceHapticViewModel
@AssistedInject
constructor(
    @Assisted quickAffordanceViewModel: Flow<KeyguardQuickAffordanceViewModel>,
    private val quickAffordanceInteractor: KeyguardQuickAffordanceInteractor,
) {

    private val activatedHistory = MutableStateFlow(ActivatedHistory(false))

    private val launchingHapticState: Flow<HapticState> =
        combine(
                quickAffordanceViewModel.map { it.configKey },
                quickAffordanceInteractor.launchingFromTriggeredResult,
            ) { key, launchingResult ->
                val validKey = key != null && key == launchingResult?.configKey
                if (validKey && launchingResult?.launched == true) {
                    HapticState.LAUNCH
                } else {
                    HapticState.NO_HAPTICS
                }
            }
            .distinctUntilChanged()

    private val toggleHapticState: Flow<HapticState> =
        activatedHistory
            .map { history ->
                when {
                    history.previousValue == false && history.currentValue -> HapticState.TOGGLE_ON
                    history.previousValue == true && !history.currentValue -> HapticState.TOGGLE_OFF
                    else -> HapticState.NO_HAPTICS
                }
            }
            .distinctUntilChanged()

    val quickAffordanceHapticState =
        merge(launchingHapticState, toggleHapticState).distinctUntilChanged()

    fun resetLaunchingFromTriggeredResult() =
        quickAffordanceInteractor.setLaunchingFromTriggeredResult(null)

    fun updateActivatedHistory(isActivated: Boolean) {
        activatedHistory.value =
            ActivatedHistory(
                currentValue = isActivated,
                previousValue = activatedHistory.value.currentValue,
            )
    }

    enum class HapticState {
        TOGGLE_ON,
        TOGGLE_OFF,
        LAUNCH,
        NO_HAPTICS,
    }

    private data class ActivatedHistory(
        val currentValue: Boolean,
        val previousValue: Boolean? = null,
    )

    @AssistedFactory
    interface Factory {
        fun create(
            quickAffordanceViewModel: Flow<KeyguardQuickAffordanceViewModel>
        ): KeyguardQuickAffordanceHapticViewModel
    }
}
