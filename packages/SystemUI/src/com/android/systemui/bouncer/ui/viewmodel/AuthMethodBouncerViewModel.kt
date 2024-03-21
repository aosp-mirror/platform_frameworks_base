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

import android.annotation.StringRes
import com.android.systemui.authentication.domain.interactor.AuthenticationResult
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.bouncer.domain.interactor.BouncerInteractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class AuthMethodBouncerViewModel(
    protected val viewModelScope: CoroutineScope,
    protected val interactor: BouncerInteractor,

    /**
     * Whether user input is enabled.
     *
     * If `false`, user input should be completely ignored in the UI as the user is "locked out" of
     * being able to attempt to unlock the device.
     */
    val isInputEnabled: StateFlow<Boolean>,
) {

    private val _animateFailure = MutableStateFlow(false)
    /**
     * Whether a failure animation should be shown. Once consumed, the UI must call
     * [onFailureAnimationShown] to consume this state.
     */
    val animateFailure: StateFlow<Boolean> = _animateFailure.asStateFlow()

    /** The authentication method that corresponds to this view model. */
    abstract val authenticationMethod: AuthenticationMethodModel

    /**
     * String resource ID of the failure message to be shown during lockout.
     *
     * The message must include 2 number parameters: the first one indicating how many unsuccessful
     * attempts were made, and the second one indicating in how many seconds lockout will expire.
     */
    @get:StringRes abstract val lockoutMessageId: Int

    /**
     * Notifies that the UI has been hidden from the user (after any transitions have completed).
     */
    open fun onHidden() {
        clearInput()
    }

    /** Notifies that the user has placed down a pointer. */
    fun onDown() {
        interactor.onDown()
    }

    /**
     * Notifies that the failure animation has been shown. This should be called to consume a `true`
     * value in [animateFailure].
     */
    fun onFailureAnimationShown() {
        _animateFailure.value = false
    }

    /** Clears any previously-entered input. */
    protected abstract fun clearInput()

    /** Returns the input entered so far. */
    protected abstract fun getInput(): List<Any>

    /**
     * Attempts to authenticate the user using the current input value.
     *
     * @see BouncerInteractor.authenticate
     */
    protected fun tryAuthenticate(
        input: List<Any> = getInput(),
        useAutoConfirm: Boolean = false,
    ) {
        viewModelScope.launch {
            val authenticationResult = interactor.authenticate(input, useAutoConfirm)
            if (authenticationResult == AuthenticationResult.SKIPPED && useAutoConfirm) {
                return@launch
            }
            _animateFailure.value = authenticationResult != AuthenticationResult.SUCCEEDED

            clearInput()
        }
    }
}
