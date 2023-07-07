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

import androidx.annotation.FloatRange
import com.android.systemui.multishade.domain.interactor.MultiShadeInteractor
import com.android.systemui.multishade.shared.model.ProxiedInputModel
import com.android.systemui.multishade.shared.model.ShadeConfig
import com.android.systemui.multishade.shared.model.ShadeId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Models UI state for a single shade. */
class ShadeViewModel(
    viewModelScope: CoroutineScope,
    private val shadeId: ShadeId,
    private val interactor: MultiShadeInteractor,
) {
    /** Whether the shade is visible. */
    val isVisible: StateFlow<Boolean> =
        interactor
            .isVisible(shadeId)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = false,
            )

    /** Whether swiping on the shade UI is currently enabled. */
    val isSwipingEnabled: StateFlow<Boolean> =
        interactor
            .isNonProxiedInputAllowed(shadeId)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = false,
            )

    /** Whether the shade must be collapsed immediately. */
    val isForceCollapsed: Flow<Boolean> =
        interactor.isForceCollapsed(shadeId).distinctUntilChanged()

    /** The width of the shade. */
    val width: StateFlow<Size> =
        interactor.shadeConfig
            .map { shadeWidth(it) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = shadeWidth(interactor.shadeConfig.value),
            )

    /**
     * The amount that the user must swipe up when the shade is fully expanded to automatically
     * collapse once the user lets go of the shade. If the user swipes less than this amount, the
     * shade will automatically revert back to fully expanded once the user stops swiping.
     */
    val swipeCollapseThreshold: StateFlow<Float> =
        interactor.shadeConfig
            .map { it.swipeCollapseThreshold }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = interactor.shadeConfig.value.swipeCollapseThreshold,
            )

    /**
     * The amount that the user must swipe down when the shade is fully collapsed to automatically
     * expand once the user lets go of the shade. If the user swipes less than this amount, the
     * shade will automatically revert back to fully collapsed once the user stops swiping.
     */
    val swipeExpandThreshold: StateFlow<Float> =
        interactor.shadeConfig
            .map { it.swipeExpandThreshold }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = interactor.shadeConfig.value.swipeExpandThreshold,
            )

    /**
     * Proxied input affecting the shade. This is input coming from sources outside of system UI
     * (for example, swiping down on the Launcher or from the status bar) or outside the UI of any
     * shade (for example, the scrim that's shown behind the shades).
     */
    val proxiedInput: Flow<ProxiedInputModel?> =
        interactor
            .proxiedInput(shadeId)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = null,
            )

    /** Notifies that the expansion amount for the shade has changed. */
    fun onExpansionChanged(
        expansion: Float,
    ) {
        interactor.setExpansion(shadeId, expansion.coerceIn(0f, 1f))
    }

    /** Notifies that a drag gesture has started. */
    fun onDragStarted() {
        interactor.onUserInteractionStarted(shadeId)
    }

    /** Notifies that a drag gesture has ended. */
    fun onDragEnded() {
        interactor.onUserInteractionEnded(shadeId = shadeId)
    }

    private fun shadeWidth(shadeConfig: ShadeConfig): Size {
        return when (shadeId) {
            ShadeId.LEFT ->
                Size.Pixels((shadeConfig as? ShadeConfig.DualShadeConfig)?.leftShadeWidthPx ?: 0)
            ShadeId.RIGHT ->
                Size.Pixels((shadeConfig as? ShadeConfig.DualShadeConfig)?.rightShadeWidthPx ?: 0)
            ShadeId.SINGLE -> Size.Fraction(1f)
        }
    }

    sealed class Size {
        data class Fraction(
            @FloatRange(from = 0.0, to = 1.0) val fraction: Float,
        ) : Size()
        data class Pixels(
            val pixels: Int,
        ) : Size()
    }
}
