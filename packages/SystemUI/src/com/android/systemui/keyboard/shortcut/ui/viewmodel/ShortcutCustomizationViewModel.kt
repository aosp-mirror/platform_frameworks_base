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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.type
import com.android.systemui.keyboard.shortcut.domain.interactor.ShortcutCustomizationInteractor
import com.android.systemui.keyboard.shortcut.shared.model.KeyCombination
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCustomizationRequestInfo
import com.android.systemui.keyboard.shortcut.ui.model.ShortcutCustomizationUiState
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

class ShortcutCustomizationViewModel
@AssistedInject
constructor(private val shortcutCustomizationInteractor: ShortcutCustomizationInteractor) {
    private val _shortcutBeingCustomized = mutableStateOf<ShortcutCustomizationRequestInfo?>(null)

    private val _shortcutCustomizationUiState =
        MutableStateFlow<ShortcutCustomizationUiState>(ShortcutCustomizationUiState.Inactive)

    val shortcutCustomizationUiState =
        shortcutCustomizationInteractor.pressedKeys
            .map { keys ->
                // Note that Action Key is excluded as it's already displayed on the UI
                keys.filter {
                    it != shortcutCustomizationInteractor.getDefaultCustomShortcutModifierKey()
                }
            }
            .combine(_shortcutCustomizationUiState) { keys, uiState ->
                if (uiState is ShortcutCustomizationUiState.AddShortcutDialog) {
                    uiState.copy(pressedKeys = keys)
                } else {
                    uiState
                }
            }

    fun onShortcutCustomizationRequested(requestInfo: ShortcutCustomizationRequestInfo) {
        when (requestInfo) {
            is ShortcutCustomizationRequestInfo.Add -> {
                _shortcutCustomizationUiState.value =
                    ShortcutCustomizationUiState.AddShortcutDialog(
                        shortcutLabel = requestInfo.label,
                        shouldShowErrorMessage = false,
                        isValidKeyCombination = false,
                        defaultCustomShortcutModifierKey =
                            shortcutCustomizationInteractor.getDefaultCustomShortcutModifierKey(),
                        isDialogShowing = false,
                        pressedKeys = emptyList(),
                    )
                _shortcutBeingCustomized.value = requestInfo
            }
        }
    }

    fun onAddShortcutDialogShown() {
        _shortcutCustomizationUiState.update { uiState ->
            (uiState as? ShortcutCustomizationUiState.AddShortcutDialog)?.copy(
                isDialogShowing = true
            ) ?: uiState
        }
    }

    fun onDialogDismissed() {
        _shortcutBeingCustomized.value = null
        _shortcutCustomizationUiState.value = ShortcutCustomizationUiState.Inactive
        shortcutCustomizationInteractor.updateUserSelectedKeyCombination(null)
    }

    fun onKeyPressed(keyEvent: KeyEvent): Boolean {
        if ((keyEvent.isMetaPressed && keyEvent.type == KeyEventType.KeyDown)) {
            updatePressedKeys(keyEvent)
            return true
        }
        return false
    }

    private fun updatePressedKeys(keyEvent: KeyEvent) {
        val isModifier = SUPPORTED_MODIFIERS.contains(keyEvent.key)
        val keyCombination =
            KeyCombination(
                modifiers = keyEvent.nativeKeyEvent.modifiers,
                keyCode = if (!isModifier) keyEvent.key.nativeKeyCode else null,
            )
        shortcutCustomizationInteractor.updateUserSelectedKeyCombination(keyCombination)
    }

    @AssistedFactory
    interface Factory {
        fun create(): ShortcutCustomizationViewModel
    }

    companion object {
        private val SUPPORTED_MODIFIERS =
            listOf(
                Key.MetaLeft,
                Key.MetaRight,
                Key.CtrlRight,
                Key.CtrlLeft,
                Key.AltLeft,
                Key.AltRight,
                Key.ShiftLeft,
                Key.ShiftRight,
                Key.Function,
                Key.Symbol,
            )
    }
}
