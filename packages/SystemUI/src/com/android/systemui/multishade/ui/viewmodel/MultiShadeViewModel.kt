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

package com.android.systemui.multishade.ui.viewmodel

import com.android.systemui.multishade.domain.interactor.MultiShadeInteractor
import com.android.systemui.multishade.shared.model.ProxiedInputModel
import com.android.systemui.multishade.shared.model.ShadeConfig
import com.android.systemui.multishade.shared.model.ShadeId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Models UI state for UI that supports multi (or single) shade. */
@OptIn(ExperimentalCoroutinesApi::class)
class MultiShadeViewModel(
    viewModelScope: CoroutineScope,
    private val interactor: MultiShadeInteractor,
) {
    /** Models UI state for the single shade. */
    val singleShade =
        ShadeViewModel(
            viewModelScope,
            ShadeId.SINGLE,
            interactor,
        )

    /** Models UI state for the shade on the left-hand side. */
    val leftShade =
        ShadeViewModel(
            viewModelScope,
            ShadeId.LEFT,
            interactor,
        )

    /** Models UI state for the shade on the right-hand side. */
    val rightShade =
        ShadeViewModel(
            viewModelScope,
            ShadeId.RIGHT,
            interactor,
        )

    /** The amount of alpha that the scrim should have. This is a value between `0` and `1`. */
    val scrimAlpha: StateFlow<Float> =
        combine(
                interactor.maxShadeExpansion,
                interactor.shadeConfig
                    .map { it as? ShadeConfig.DualShadeConfig }
                    .map { dualShadeConfigOrNull -> dualShadeConfigOrNull?.scrimAlpha ?: 0f },
                ::Pair,
            )
            .map { (anyShadeExpansion, scrimAlpha) ->
                (anyShadeExpansion * scrimAlpha).coerceIn(0f, 1f)
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = 0f,
            )

    /** Whether the scrim should accept touch events. */
    val isScrimEnabled: StateFlow<Boolean> =
        interactor.shadeConfig
            .flatMapLatest { shadeConfig ->
                when (shadeConfig) {
                    // In the dual shade configuration, the scrim is enabled when the expansion is
                    // greater than zero on any one of the shades.
                    is ShadeConfig.DualShadeConfig -> interactor.isAnyShadeExpanded
                    // No scrim in the single shade configuration.
                    is ShadeConfig.SingleShadeConfig -> flowOf(false)
                }
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = false,
            )

    /** Notifies that the scrim has been touched. */
    fun onScrimTouched(proxiedInput: ProxiedInputModel) {
        if (!isScrimEnabled.value) {
            return
        }

        interactor.sendProxiedInput(proxiedInput)
    }
}
