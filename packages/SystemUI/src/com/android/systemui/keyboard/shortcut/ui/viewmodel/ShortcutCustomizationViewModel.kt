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

import android.content.Context
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.type
import com.android.systemui.keyboard.shared.model.ShortcutCustomizationRequestResult
import com.android.systemui.keyboard.shortcut.domain.interactor.ShortcutCustomizationInteractor
import com.android.systemui.keyboard.shortcut.shared.model.KeyCombination
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCustomizationRequestInfo
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutKey
import com.android.systemui.keyboard.shortcut.ui.model.ShortcutCustomizationUiState
import com.android.systemui.keyboard.shortcut.ui.model.ShortcutCustomizationUiState.AddShortcutDialog
import com.android.systemui.keyboard.shortcut.ui.model.ShortcutCustomizationUiState.DeleteShortcutDialog
import com.android.systemui.keyboard.shortcut.ui.model.ShortcutCustomizationUiState.ResetShortcutDialog
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.res.R
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class ShortcutCustomizationViewModel
@AssistedInject
constructor(
    private val context: Context,
    private val shortcutCustomizationInteractor: ShortcutCustomizationInteractor,
) : ExclusiveActivatable() {
    private var keyDownEventCache: KeyEvent? = null
    private val _shortcutCustomizationUiState =
        MutableStateFlow<ShortcutCustomizationUiState>(ShortcutCustomizationUiState.Inactive)

    val shortcutCustomizationUiState = _shortcutCustomizationUiState.asStateFlow()

    fun onShortcutCustomizationRequested(requestInfo: ShortcutCustomizationRequestInfo) {
        shortcutCustomizationInteractor.onCustomizationRequested(requestInfo)
        when (requestInfo) {
            is ShortcutCustomizationRequestInfo.SingleShortcutCustomization.Add -> {
                _shortcutCustomizationUiState.value =
                    AddShortcutDialog(
                        shortcutLabel = requestInfo.label,
                        defaultCustomShortcutModifierKey =
                            shortcutCustomizationInteractor.getDefaultCustomShortcutModifierKey(),
                        pressedKeys = emptyList(),
                    )
            }

            is ShortcutCustomizationRequestInfo.SingleShortcutCustomization.Delete -> {
                _shortcutCustomizationUiState.value = DeleteShortcutDialog
            }

            ShortcutCustomizationRequestInfo.Reset -> {
                _shortcutCustomizationUiState.value = ResetShortcutDialog
            }
        }
    }

    fun onDialogDismissed() {
        _shortcutCustomizationUiState.value = ShortcutCustomizationUiState.Inactive
        shortcutCustomizationInteractor.onCustomizationRequested(null)
        clearSelectedKeyCombination()
    }

    fun onShortcutKeyCombinationSelected(keyEvent: KeyEvent): Boolean {
        if (isModifier(keyEvent)) {
            return false
        }
        if (keyEvent.isMetaPressed && keyEvent.type == KeyEventType.KeyDown) {
            keyDownEventCache = keyEvent
            return true
        } else if (keyEvent.type == KeyEventType.KeyUp && keyEvent.key == keyDownEventCache?.key) {
            updatePressedKeys(keyDownEventCache!!)
            clearKeyDownEventCache()
            return true
        }
        return false
    }

    suspend fun onSetShortcut() {
        val result = shortcutCustomizationInteractor.confirmAndSetShortcutCurrentlyBeingCustomized()
        _shortcutCustomizationUiState.update { uiState ->
            when (result) {
                ShortcutCustomizationRequestResult.SUCCESS -> ShortcutCustomizationUiState.Inactive
                ShortcutCustomizationRequestResult.ERROR_RESERVED_COMBINATION -> {
                    getUiStateWithErrorMessage(
                        uiState = uiState,
                        errorMessage =
                            context.getString(
                                R.string.shortcut_customizer_key_combination_in_use_error_message
                            ),
                    )
                }

                ShortcutCustomizationRequestResult.ERROR_OTHER ->
                    getUiStateWithErrorMessage(
                        uiState = uiState,
                        errorMessage =
                            context.getString(R.string.shortcut_customizer_generic_error_message),
                    )
            }
        }
    }

    suspend fun deleteShortcutCurrentlyBeingCustomized() {
        val result = shortcutCustomizationInteractor.deleteShortcutCurrentlyBeingCustomized()

        _shortcutCustomizationUiState.update { uiState ->
            when (result) {
                ShortcutCustomizationRequestResult.SUCCESS -> ShortcutCustomizationUiState.Inactive
                else -> uiState
            }
        }
    }

    suspend fun resetAllCustomShortcuts() {
        val result = shortcutCustomizationInteractor.resetAllCustomShortcuts()

        _shortcutCustomizationUiState.update { uiState ->
            when (result) {
                ShortcutCustomizationRequestResult.SUCCESS -> ShortcutCustomizationUiState.Inactive
                else -> uiState
            }
        }
    }

    fun clearSelectedKeyCombination() {
        shortcutCustomizationInteractor.updateUserSelectedKeyCombination(null)
    }

    private fun getUiStateWithErrorMessage(
        uiState: ShortcutCustomizationUiState,
        errorMessage: String,
    ): ShortcutCustomizationUiState {
        return (uiState as? AddShortcutDialog)?.copy(errorMessage = errorMessage) ?: uiState
    }

    private fun isModifier(keyEvent: KeyEvent) = SUPPORTED_MODIFIERS.contains(keyEvent.key)

    private fun updatePressedKeys(keyEvent: KeyEvent) {
        val keyCombination =
            KeyCombination(
                modifiers = keyEvent.nativeKeyEvent.modifiers,
                keyCode = if (!isModifier(keyEvent)) keyEvent.key.nativeKeyCode else null,
            )
        shortcutCustomizationInteractor.updateUserSelectedKeyCombination(keyCombination)
    }

    private fun clearKeyDownEventCache() {
        keyDownEventCache = null
    }

    private suspend fun isSelectedKeyCombinationAvailable() =
        shortcutCustomizationInteractor.isSelectedKeyCombinationAvailable()

    @AssistedFactory
    interface Factory {
        fun create(): ShortcutCustomizationViewModel
    }

    override suspend fun onActivated(): Nothing {
        shortcutCustomizationInteractor.pressedKeys.collect {
            val keys = filterDefaultCustomShortcutModifierKey(it)
            val errorMessage = getErrorMessageForPressedKeys(keys)

            _shortcutCustomizationUiState.update { uiState ->
                if (uiState is AddShortcutDialog) {
                    uiState.copy(pressedKeys = keys, errorMessage = errorMessage)
                } else {
                    uiState
                }
            }
        }
    }

    private suspend fun getErrorMessageForPressedKeys(keys: List<ShortcutKey>): String {
        return if (keys.isEmpty() or isSelectedKeyCombinationAvailable()) {
            ""
        }
        else {
            context.getString(R.string.shortcut_customizer_key_combination_in_use_error_message)
        }
    }

    private fun filterDefaultCustomShortcutModifierKey(keys: List<ShortcutKey>) =
        keys.filter { it != shortcutCustomizationInteractor.getDefaultCustomShortcutModifierKey() }

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
