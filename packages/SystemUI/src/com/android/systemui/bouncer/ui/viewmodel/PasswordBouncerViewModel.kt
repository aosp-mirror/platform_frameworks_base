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

package com.android.systemui.bouncer.ui.viewmodel

import androidx.annotation.VisibleForTesting
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.bouncer.domain.interactor.BouncerInteractor
import com.android.systemui.inputmethod.domain.interactor.InputMethodInteractor
import com.android.systemui.res.R
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import com.android.systemui.util.kotlin.onSubscriberAdded
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Holds UI state and handles user input for the password bouncer UI. */
class PasswordBouncerViewModel(
    viewModelScope: CoroutineScope,
    isInputEnabled: StateFlow<Boolean>,
    interactor: BouncerInteractor,
    private val onIntentionalUserInput: () -> Unit,
    private val inputMethodInteractor: InputMethodInteractor,
    private val selectedUserInteractor: SelectedUserInteractor,
) :
    AuthMethodBouncerViewModel(
        viewModelScope = viewModelScope,
        interactor = interactor,
        isInputEnabled = isInputEnabled,
    ) {

    private val _password = MutableStateFlow("")

    /** The password entered so far. */
    val password: StateFlow<String> = _password.asStateFlow()

    override val authenticationMethod = AuthenticationMethodModel.Password

    override val lockoutMessageId = R.string.kg_too_many_failed_password_attempts_dialog_message

    /** Informs the UI whether the input method switcher button should be visible. */
    val isImeSwitcherButtonVisible: StateFlow<Boolean> = imeSwitcherRefreshingFlow()

    /** Whether the text field element currently has focus. */
    private val isTextFieldFocused = MutableStateFlow(false)

    /** Whether the UI should request focus on the text field element. */
    val isTextFieldFocusRequested =
        combine(isInputEnabled, isTextFieldFocused) { hasInput, hasFocus -> hasInput && !hasFocus }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = isInputEnabled.value && !isTextFieldFocused.value,
            )

    /** The ID of the currently-selected user. */
    val selectedUserId: StateFlow<Int> =
        selectedUserInteractor.selectedUser.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = selectedUserInteractor.getSelectedUserId(),
        )

    override fun onHidden() {
        super.onHidden()
        isTextFieldFocused.value = false
    }

    override fun clearInput() {
        _password.value = ""
    }

    override fun getInput(): List<Any> {
        return _password.value.toCharArray().toList()
    }

    /** Notifies that the user has changed the password input. */
    fun onPasswordInputChanged(newPassword: String) {
        if (newPassword.isNotEmpty()) {
            onIntentionalUserInput()
        }

        _password.value = newPassword
    }

    /** Notifies that the user clicked the button to change the input method. */
    fun onImeSwitcherButtonClicked(displayId: Int) {
        viewModelScope.launch {
            inputMethodInteractor.showInputMethodPicker(displayId, showAuxiliarySubtypes = false)
        }
    }

    /** Notifies that the user has pressed the key for attempting to authenticate the password. */
    fun onAuthenticateKeyPressed() {
        if (_password.value.isNotEmpty()) {
            tryAuthenticate()
        }
    }

    /** Notifies that the user has dismissed the software keyboard (IME). */
    fun onImeDismissed() {
        viewModelScope.launch { interactor.onImeHiddenByUser() }
    }

    /** Notifies that the password text field has gained or lost focus. */
    fun onTextFieldFocusChanged(isFocused: Boolean) {
        isTextFieldFocused.value = isFocused
    }

    /**
     * Whether the input method switcher button should be displayed in the password bouncer UI. The
     * value may be stale at the moment of subscription to this flow, but it is guaranteed to be
     * shortly updated with a fresh value.
     *
     * Note: Each added subscription triggers an IPC call in the background, so this should only be
     * subscribed to by the UI once in its lifecycle (i.e. when the bouncer is shown).
     */
    private fun imeSwitcherRefreshingFlow(): StateFlow<Boolean> {
        val isImeSwitcherButtonVisible = MutableStateFlow(value = false)
        viewModelScope.launch {
            // Re-fetch the currently-enabled IMEs whenever the selected user changes, and whenever
            // the UI subscribes to the `isImeSwitcherButtonVisible` flow.
            combine(
                    // InputMethodManagerService sometimes takes some time to update its internal
                    // state when the selected user changes. As a workaround, delay fetching the IME
                    // info.
                    selectedUserInteractor.selectedUser.onEach { delay(DELAY_TO_FETCH_IMES) },
                    isImeSwitcherButtonVisible.onSubscriberAdded()
                ) { selectedUserId, _ ->
                    inputMethodInteractor.hasMultipleEnabledImesOrSubtypes(selectedUserId)
                }
                .collect { isImeSwitcherButtonVisible.value = it }
        }
        return isImeSwitcherButtonVisible.asStateFlow()
    }

    companion object {
        @VisibleForTesting val DELAY_TO_FETCH_IMES = 300.milliseconds
    }
}
