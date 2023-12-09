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

package com.android.systemui.bouncer.domain.interactor

import android.content.Context
import com.android.systemui.authentication.domain.interactor.AuthenticationInteractor
import com.android.systemui.authentication.domain.interactor.AuthenticationResult
import com.android.systemui.authentication.shared.model.AuthenticationLockoutModel
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.bouncer.data.repository.BouncerRepository
import com.android.systemui.classifier.FalsingClassifier
import com.android.systemui.classifier.domain.interactor.FalsingInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.domain.interactor.KeyguardFaceAuthInteractor
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.res.R
import com.android.systemui.scene.shared.flag.SceneContainerFlags
import com.android.systemui.util.kotlin.pairwise
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Encapsulates business logic and application state accessing use-cases. */
@SysUISingleton
class BouncerInteractor
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    @Application private val applicationContext: Context,
    private val repository: BouncerRepository,
    private val authenticationInteractor: AuthenticationInteractor,
    private val keyguardFaceAuthInteractor: KeyguardFaceAuthInteractor,
    flags: SceneContainerFlags,
    private val falsingInteractor: FalsingInteractor,
    private val powerInteractor: PowerInteractor,
    private val simBouncerInteractor: SimBouncerInteractor,
) {

    /** The user-facing message to show in the bouncer. */
    val message: StateFlow<String?> =
        combine(repository.message, authenticationInteractor.lockout) { message, lockout ->
                messageOrLockoutMessage(message, lockout)
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue =
                    messageOrLockoutMessage(
                        repository.message.value,
                        authenticationInteractor.lockout.value,
                    )
            )

    /**
     * The current authentication lockout (aka "throttling") state, set when the user has to wait
     * before being able to try another authentication attempt. `null` indicates lockout isn't
     * active.
     */
    val lockout: StateFlow<AuthenticationLockoutModel?> = authenticationInteractor.lockout

    /** Whether the auto confirm feature is enabled for the currently-selected user. */
    val isAutoConfirmEnabled: StateFlow<Boolean> = authenticationInteractor.isAutoConfirmEnabled

    /** The length of the hinted PIN, or `null`, if pin length hint should not be shown. */
    val hintedPinLength: StateFlow<Int?> = authenticationInteractor.hintedPinLength

    /** Whether the pattern should be visible for the currently-selected user. */
    val isPatternVisible: StateFlow<Boolean> = authenticationInteractor.isPatternVisible

    /** Whether the "enhanced PIN privacy" setting is enabled for the current user. */
    val isPinEnhancedPrivacyEnabled: StateFlow<Boolean> =
        authenticationInteractor.isPinEnhancedPrivacyEnabled

    /** Whether the user switcher should be displayed within the bouncer UI on large screens. */
    val isUserSwitcherVisible: Boolean
        get() = repository.isUserSwitcherVisible

    private val _onImeHiddenByUser = MutableSharedFlow<Unit>()
    /** Emits a [Unit] each time the IME (keyboard) is hidden by the user. */
    val onImeHiddenByUser: SharedFlow<Unit> = _onImeHiddenByUser

    init {
        if (flags.isEnabled()) {
            // Clear the message if moved from locked-out to no-longer locked-out.
            applicationScope.launch {
                lockout.pairwise().collect { (previous, current) ->
                    if (previous != null && current == null) {
                        clearMessage()
                    }
                }
            }
        }
    }

    /** Notifies that the user has places down a pointer, not necessarily dragging just yet. */
    fun onDown() {
        falsingInteractor.avoidGesture()
    }

    /**
     * Notifies of "intentional" (i.e. non-false) user interaction with the UI which is very likely
     * to be real user interaction with the bouncer and not the result of a false touch in the
     * user's pocket or by the user's face while holding their device up to their ear.
     */
    fun onIntentionalUserInput() {
        keyguardFaceAuthInteractor.onPrimaryBouncerUserInput()
        powerInteractor.onUserTouch()
        falsingInteractor.updateFalseConfidence(FalsingClassifier.Result.passed(0.6))
    }

    /**
     * Notifies of false input which is very likely to be the result of a false touch in the user's
     * pocket or by the user's face while holding their device up to their ear.
     */
    fun onFalseUserInput() {
        falsingInteractor.updateFalseConfidence(
            FalsingClassifier.Result.falsed(
                /* confidence= */ 0.7,
                /* context= */ javaClass.simpleName,
                /* reason= */ "empty pattern input",
            )
        )
    }

    fun setMessage(message: String?) {
        repository.setMessage(message)
    }

    /**
     * Resets the user-facing message back to the default according to the current authentication
     * method.
     */
    fun resetMessage() {
        applicationScope.launch {
            repository.setMessage(promptMessage(authenticationInteractor.getAuthenticationMethod()))
        }
    }

    /** Removes the user-facing message. */
    fun clearMessage() {
        repository.setMessage(null)
    }

    /**
     * Attempts to authenticate based on the given user input.
     *
     * If the input is correct, the device will be unlocked and the lock screen and bouncer will be
     * dismissed and hidden.
     *
     * If [tryAutoConfirm] is `true`, authentication is attempted if and only if the auth method
     * supports auto-confirming, and the input's length is at least the required length. Otherwise,
     * `AuthenticationResult.SKIPPED` is returned.
     *
     * @param input The input from the user to try to authenticate with. This can be a list of
     *   different things, based on the current authentication method.
     * @param tryAutoConfirm `true` if called while the user inputs the code, without an explicit
     *   request to validate.
     * @return The result of this authentication attempt.
     */
    suspend fun authenticate(
        input: List<Any>,
        tryAutoConfirm: Boolean = false,
    ): AuthenticationResult {
        if (input.isEmpty()) {
            return AuthenticationResult.SKIPPED
        }

        if (authenticationInteractor.getAuthenticationMethod() == AuthenticationMethodModel.Sim) {
            // We authenticate sim in SimInteractor
            return AuthenticationResult.SKIPPED
        }

        // Switching to the application scope here since this method is often called from
        // view-models, whose lifecycle (and thus scope) is shorter than this interactor.
        // This allows the task to continue running properly even when the calling scope has been
        // cancelled.
        return applicationScope
            .async {
                val authResult = authenticationInteractor.authenticate(input, tryAutoConfirm)
                if (
                    authResult == AuthenticationResult.FAILED ||
                        (authResult == AuthenticationResult.SKIPPED && !tryAutoConfirm)
                ) {
                    showErrorMessage()
                }
                authResult
            }
            .await()
    }

    /**
     * Shows the error message.
     *
     * Callers should use this instead of [authenticate] when they know ahead of time that an auth
     * attempt will fail but aren't interested in the other side effects like triggering lockout.
     * For example, if the user entered a pattern that's too short, the system can show the error
     * message without having the attempt trigger lockout.
     */
    private suspend fun showErrorMessage() {
        repository.setMessage(errorMessage(authenticationInteractor.getAuthenticationMethod()))
    }

    /** Notifies that the input method editor (software keyboard) has been hidden by the user. */
    suspend fun onImeHiddenByUser() {
        _onImeHiddenByUser.emit(Unit)
    }

    private fun promptMessage(authMethod: AuthenticationMethodModel): String {
        return when (authMethod) {
            is AuthenticationMethodModel.Sim -> simBouncerInteractor.getDefaultMessage()
            is AuthenticationMethodModel.Pin ->
                applicationContext.getString(R.string.keyguard_enter_your_pin)
            is AuthenticationMethodModel.Password ->
                applicationContext.getString(R.string.keyguard_enter_your_password)
            is AuthenticationMethodModel.Pattern ->
                applicationContext.getString(R.string.keyguard_enter_your_pattern)
            else -> ""
        }
    }

    private fun errorMessage(authMethod: AuthenticationMethodModel): String {
        return when (authMethod) {
            is AuthenticationMethodModel.Pin -> applicationContext.getString(R.string.kg_wrong_pin)
            is AuthenticationMethodModel.Password ->
                applicationContext.getString(R.string.kg_wrong_password)
            is AuthenticationMethodModel.Pattern ->
                applicationContext.getString(R.string.kg_wrong_pattern)
            else -> ""
        }
    }

    private fun messageOrLockoutMessage(
        message: String?,
        lockoutModel: AuthenticationLockoutModel?,
    ): String {
        return when {
            lockoutModel != null ->
                applicationContext.getString(
                    com.android.internal.R.string.lockscreen_too_many_failed_attempts_countdown,
                    lockoutModel.remainingSeconds,
                )
            message != null -> message
            else -> ""
        }
    }
}
