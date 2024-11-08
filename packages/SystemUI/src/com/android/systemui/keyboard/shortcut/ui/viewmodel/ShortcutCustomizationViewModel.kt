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

package com.android.systemui.keyboard.shortcut.ui.viewmodel

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.KeyEvent
import com.android.systemui.keyboard.shortcut.domain.interactor.ShortcutCustomizationInteractor
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutInfo
import com.android.systemui.keyboard.shortcut.ui.model.ShortcutCustomizationUiState
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class ShortcutCustomizationViewModel
@AssistedInject
constructor(private val shortcutCustomizationInteractor: ShortcutCustomizationInteractor) {
    private val _shortcutBeingCustomized = mutableStateOf<ShortcutInfo?>(null)

    private val _shortcutCustomizationUiState =
        MutableStateFlow<ShortcutCustomizationUiState>(ShortcutCustomizationUiState.Inactive)

    val shortcutCustomizationUiState = _shortcutCustomizationUiState.asStateFlow()

    fun onAddShortcutDialogRequested(shortcutBeingCustomized: ShortcutInfo) {
        _shortcutCustomizationUiState.value =
            ShortcutCustomizationUiState.AddShortcutDialog(
                shortcutLabel = shortcutBeingCustomized.label,
                shouldShowErrorMessage = false,
                isValidKeyCombination = false,
                defaultCustomShortcutModifierKey =
                    shortcutCustomizationInteractor.getDefaultCustomShortcutModifierKey(),
                isDialogShowing = false,
            )

        _shortcutBeingCustomized.value = shortcutBeingCustomized
    }

    fun onAddShortcutDialogShown() {
        _shortcutCustomizationUiState.update { uiState ->
            (uiState as? ShortcutCustomizationUiState.AddShortcutDialog)
                ?.let { it.copy(isDialogShowing = true) }
                ?: uiState
        }
    }

    fun onAddShortcutDialogDismissed() {
        _shortcutBeingCustomized.value = null
        _shortcutCustomizationUiState.value = ShortcutCustomizationUiState.Inactive
    }

    fun onKeyPressed(keyEvent: KeyEvent): Boolean {
        // TODO Not yet implemented b/373638584
        return false
    }

    @AssistedFactory
    interface Factory {
        fun create(): ShortcutCustomizationViewModel
    }
}
