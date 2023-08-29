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
import com.android.systemui.R
import com.android.systemui.authentication.domain.interactor.AuthenticationInteractor
import com.android.systemui.authentication.domain.model.AuthenticationMethodModel
import com.android.systemui.authentication.shared.model.AuthenticationThrottlingModel
import com.android.systemui.bouncer.data.repository.BouncerRepository
import com.android.systemui.classifier.FalsingClassifier
import com.android.systemui.classifier.domain.interactor.FalsingInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.shared.model.SceneModel
import com.android.systemui.util.kotlin.pairwise
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
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
    private val sceneInteractor: SceneInteractor,
    featureFlags: FeatureFlags,
    private val falsingInteractor: FalsingInteractor,
) {

    /** The user-facing message to show in the bouncer. */
    val message: StateFlow<String?> =
        combine(
                repository.message,
                authenticationInteractor.isThrottled,
                authenticationInteractor.throttling,
            ) { message, isThrottled, throttling ->
                messageOrThrottlingMessage(message, isThrottled, throttling)
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue =
                    messageOrThrottlingMessage(
                        repository.message.value,
                        authenticationInteractor.isThrottled.value,
                        authenticationInteractor.throttling.value,
                    )
            )

    /** The current authentication throttling state, only meaningful if [isThrottled] is `true`. */
    val throttling: StateFlow<AuthenticationThrottlingModel> = authenticationInteractor.throttling

    /**
     * Whether currently throttled and the user has to wait before being able to try another
     * authentication attempt.
     */
    val isThrottled: StateFlow<Boolean> = authenticationInteractor.isThrottled

    /** Whether the auto confirm feature is enabled for the currently-selected user. */
    val isAutoConfirmEnabled: StateFlow<Boolean> = authenticationInteractor.isAutoConfirmEnabled

    /** The length of the hinted PIN, or `null`, if pin length hint should not be shown. */
    val hintedPinLength: StateFlow<Int?> = authenticationInteractor.hintedPinLength

    /** Whether the pattern should be visible for the currently-selected user. */
    val isPatternVisible: StateFlow<Boolean> = authenticationInteractor.isPatternVisible

    init {
        if (featureFlags.isEnabled(Flags.SCENE_CONTAINER)) {
            // Clear the message if moved from throttling to no-longer throttling.
            applicationScope.launch {
                isThrottled.pairwise().collect { (wasThrottled, currentlyThrottled) ->
                    if (wasThrottled && !currentlyThrottled) {
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

    /**
     * Either shows the bouncer or unlocks the device, if the bouncer doesn't need to be shown.
     *
     * @param message An optional message to show to the user in the bouncer.
     */
    fun showOrUnlockDevice(
        message: String? = null,
    ) {
        applicationScope.launch {
            if (authenticationInteractor.isAuthenticationRequired()) {
                repository.setMessage(
                    message ?: promptMessage(authenticationInteractor.getAuthenticationMethod())
                )
                sceneInteractor.changeScene(
                    scene = SceneModel(SceneKey.Bouncer),
                    loggingReason = "request to unlock device while authentication required",
                )
            } else {
                sceneInteractor.changeScene(
                    scene = SceneModel(SceneKey.Gone),
                    loggingReason = "request to unlock device while authentication isn't required",
                )
            }
        }
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
     * supports auto-confirming, and the input's length is at least the code's length. Otherwise,
     * `null` is returned.
     *
     * @param input The input from the user to try to authenticate with. This can be a list of
     *   different things, based on the current authentication method.
     * @param tryAutoConfirm `true` if called while the user inputs the code, without an explicit
     *   request to validate.
     * @return `true` if the authentication succeeded and the device is now unlocked; `false` when
     *   authentication failed, `null` if the check was not performed.
     */
    suspend fun authenticate(
        input: List<Any>,
        tryAutoConfirm: Boolean = false,
    ): Boolean? {
        val isAuthenticated =
            authenticationInteractor.authenticate(input, tryAutoConfirm) ?: return null

        if (isAuthenticated) {
            sceneInteractor.changeScene(
                scene = SceneModel(SceneKey.Gone),
                loggingReason = "successful authentication",
            )
        } else {
            repository.setMessage(errorMessage(authenticationInteractor.getAuthenticationMethod()))
        }

        return isAuthenticated
    }

    private fun promptMessage(authMethod: AuthenticationMethodModel): String {
        return when (authMethod) {
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

    private fun messageOrThrottlingMessage(
        message: String?,
        isThrottled: Boolean,
        throttlingModel: AuthenticationThrottlingModel,
    ): String {
        return when {
            isThrottled ->
                applicationContext.getString(
                    com.android.internal.R.string.lockscreen_too_many_failed_attempts_countdown,
                    throttlingModel.remainingMs.milliseconds.inWholeSeconds,
                )
            message != null -> message
            else -> ""
        }
    }
}
