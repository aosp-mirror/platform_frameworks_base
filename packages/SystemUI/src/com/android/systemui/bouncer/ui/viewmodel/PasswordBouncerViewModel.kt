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

import com.android.systemui.bouncer.domain.interactor.BouncerInteractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Holds UI state and handles user input for the password bouncer UI. */
class PasswordBouncerViewModel(
    private val applicationScope: CoroutineScope,
    private val interactor: BouncerInteractor,
    isInputEnabled: StateFlow<Boolean>,
) :
    AuthMethodBouncerViewModel(
        isInputEnabled = isInputEnabled,
    ) {

    private val _password = MutableStateFlow("")
    /** The password entered so far. */
    val password: StateFlow<String> = _password.asStateFlow()

    /** Notifies that the UI has been shown to the user. */
    fun onShown() {
        interactor.resetMessage()
    }

    /** Notifies that the user has changed the password input. */
    fun onPasswordInputChanged(password: String) {
        if (this.password.value.isEmpty() && password.isNotEmpty()) {
            interactor.clearMessage()
        }

        _password.value = password
    }

    /** Notifies that the user has pressed the key for attempting to authenticate the password. */
    fun onAuthenticateKeyPressed() {
        val password = _password.value.toCharArray().toList()
        _password.value = ""

        applicationScope.launch {
            if (interactor.authenticate(password) != true) {
                showFailureAnimation()
            }
        }
    }
}
