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
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** Holds UI state and handles user input for the password bouncer UI. */
class PasswordBouncerViewModel
@AssistedInject
constructor(
    interactor: BouncerInteractor,
    private val inputMethodInteractor: InputMethodInteractor,
    private val selectedUserInteractor: SelectedUserInteractor,
    @Assisted isInputEnabled: StateFlow<Boolean>,
    @Assisted private val onIntentionalUserInput: () -> Unit,
) :
    AuthMethodBouncerViewModel(
        interactor = interactor,
        isInputEnabled = isInputEnabled,
        traceName = "PasswordBouncerViewModel",
    ) {

    private val _password = MutableStateFlow("")

    /** The password entered so far. */
    val password: StateFlow<String> = _password.asStateFlow()

    override val authenticationMethod = AuthenticationMethodModel.Password

    override val lockoutMessageId = R.string.kg_too_many_failed_password_attempts_dialog_message

    private val _isImeSwitcherButtonVisible = MutableStateFlow(false)
    /** Informs the UI whether the input method switcher button should be visible. */
    val isImeSwitcherButtonVisible: StateFlow<Boolean> = _isImeSwitcherButtonVisible.asStateFlow()

    /** Whether the text field element currently has focus. */
    private val isTextFieldFocused = MutableStateFlow(false)

    private val _isTextFieldFocusRequested =
        MutableStateFlow(isInputEnabled.value && !isTextFieldFocused.value)
    /** Whether the UI should request focus on the text field element. */
    val isTextFieldFocusRequested = _isTextFieldFocusRequested.asStateFlow()

    private val _selectedUserId = MutableStateFlow(selectedUserInteractor.getSelectedUserId())
    /** The ID of the currently-selected user. */
    val selectedUserId: StateFlow<Int> = _selectedUserId.asStateFlow()

    private val requests = Channel<Request>(Channel.BUFFERED)
    private var wasSuccessfullyAuthenticated = false

    override suspend fun onActivated(): Nothing {
        try {
            coroutineScope {
                launch { super.onActivated() }
                launch {
                    requests.receiveAsFlow().collect { request ->
                        when (request) {
                            is OnImeSwitcherButtonClicked -> {
                                inputMethodInteractor.showInputMethodPicker(
                                    displayId = request.displayId,
                                    showAuxiliarySubtypes = false,
                                )
                            }
                            is OnImeDismissed -> {
                                interactor.onImeHiddenByUser()
                            }
                        }
                    }
                }
                launch {
                    combine(isInputEnabled, isTextFieldFocused) { hasInput, hasFocus ->
                            hasInput && !hasFocus && !wasSuccessfullyAuthenticated
                        }
                        .collect { _isTextFieldFocusRequested.value = it }
                }
                launch {
                    selectedUserInteractor.selectedUser.collect { _selectedUserId.value = it }
                }
                launch {
                    // Re-fetch the currently-enabled IMEs whenever the selected user changes, and
                    // whenever
                    // the UI subscribes to the `isImeSwitcherButtonVisible` flow.
                    combine(
                            // InputMethodManagerService sometimes takes
                            // some time to update its internal state when the
                            // selected user changes.
                            // As a workaround, delay fetching the IME info.
                            selectedUserInteractor.selectedUser.onEach {
                                delay(DELAY_TO_FETCH_IMES)
                            },
                            _isImeSwitcherButtonVisible.onSubscriberAdded(),
                        ) { selectedUserId, _ ->
                            inputMethodInteractor.hasMultipleEnabledImesOrSubtypes(selectedUserId)
                        }
                        .collect { _isImeSwitcherButtonVisible.value = it }
                }
                awaitCancellation()
            }
        } finally {
            // reset whenever the view model is "deactivated"
            wasSuccessfullyAuthenticated = false
        }
    }

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

    override fun onSuccessfulAuthentication() {
        wasSuccessfullyAuthenticated = true
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
        requests.trySend(OnImeSwitcherButtonClicked(displayId))
    }

    /** Notifies that the user has pressed the key for attempting to authenticate the password. */
    fun onAuthenticateKeyPressed() {
        if (_password.value.isNotEmpty()) {
            tryAuthenticate()
        }
    }

    /** Notifies that the user has dismissed the software keyboard (IME). */
    fun onImeDismissed() {
        requests.trySend(OnImeDismissed)
    }

    /** Notifies that the password text field has gained or lost focus. */
    fun onTextFieldFocusChanged(isFocused: Boolean) {
        isTextFieldFocused.value = isFocused
    }

    @AssistedFactory
    interface Factory {
        fun create(
            isInputEnabled: StateFlow<Boolean>,
            onIntentionalUserInput: () -> Unit,
        ): PasswordBouncerViewModel
    }

    companion object {
        @VisibleForTesting val DELAY_TO_FETCH_IMES = 300.milliseconds
    }

    private sealed interface Request

    private data class OnImeSwitcherButtonClicked(val displayId: Int) : Request

    private data object OnImeDismissed : Request
}
