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
import com.android.app.tracing.coroutines.flow.collectLatest
import com.android.systemui.authentication.domain.interactor.AuthenticationResult
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.bouncer.domain.interactor.BouncerInteractor
import com.android.systemui.bouncer.ui.helper.BouncerHapticPlayer
import com.android.systemui.lifecycle.ExclusiveActivatable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

sealed class AuthMethodBouncerViewModel(
    protected val interactor: BouncerInteractor,

    /**
     * Whether user input is enabled.
     *
     * If `false`, user input should be completely ignored in the UI as the user is "locked out" of
     * being able to attempt to unlock the device.
     */
    val isInputEnabled: StateFlow<Boolean>,

    /** Name to use for performance tracing purposes. */
    val traceName: String,
    protected val bouncerHapticPlayer: BouncerHapticPlayer? = null,
) : ExclusiveActivatable() {

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

    private val authenticationRequests = Channel<AuthenticationRequest>(Channel.BUFFERED)

    override suspend fun onActivated(): Nothing {
        authenticationRequests.receiveAsFlow().collectLatest { request ->
            if (!isInputEnabled.value) {
                return@collectLatest
            }

            val authenticationResult =
                interactor.authenticate(
                    input = request.input,
                    tryAutoConfirm = request.useAutoConfirm,
                )

            if (authenticationResult == AuthenticationResult.SKIPPED && request.useAutoConfirm) {
                return@collectLatest
            }

            performAuthenticationHapticFeedback(authenticationResult)

            _animateFailure.value = authenticationResult != AuthenticationResult.SUCCEEDED
            clearInput()
            if (authenticationResult == AuthenticationResult.SUCCEEDED) {
                onSuccessfulAuthentication()
            }
        }
        awaitCancellation()
    }

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

    /** Invoked after a successful authentication. */
    protected open fun onSuccessfulAuthentication() = Unit

    /** Perform authentication result haptics */
    private fun performAuthenticationHapticFeedback(result: AuthenticationResult) {
        if (result == AuthenticationResult.SKIPPED) return

        bouncerHapticPlayer?.playAuthenticationFeedback(
            authenticationSucceeded = result == AuthenticationResult.SUCCEEDED
        )
    }

    /**
     * Attempts to authenticate the user using the current input value.
     *
     * @see BouncerInteractor.authenticate
     */
    protected fun tryAuthenticate(input: List<Any> = getInput(), useAutoConfirm: Boolean = false) {
        authenticationRequests.trySend(AuthenticationRequest(input, useAutoConfirm))
    }

    private data class AuthenticationRequest(val input: List<Any>, val useAutoConfirm: Boolean)
}
