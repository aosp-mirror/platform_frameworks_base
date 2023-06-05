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
import androidx.annotation.VisibleForTesting
import com.android.systemui.R
import com.android.systemui.authentication.domain.interactor.AuthenticationInteractor
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.bouncer.data.repository.BouncerRepository
import com.android.systemui.bouncer.shared.model.AuthenticationThrottledModel
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.shared.model.SceneModel
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Encapsulates business logic and application state accessing use-cases. */
class BouncerInteractor
@AssistedInject
constructor(
    @Application private val applicationScope: CoroutineScope,
    @Application private val applicationContext: Context,
    private val repository: BouncerRepository,
    private val authenticationInteractor: AuthenticationInteractor,
    private val sceneInteractor: SceneInteractor,
    @Assisted private val containerName: String,
) {

    /** The user-facing message to show in the bouncer. */
    val message: StateFlow<String?> =
        combine(
                repository.message,
                repository.throttling,
            ) { message, throttling ->
                messageOrThrottlingMessage(message, throttling)
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue =
                    messageOrThrottlingMessage(
                        repository.message.value,
                        repository.throttling.value,
                    )
            )

    /**
     * The currently-configured authentication method. This determines how the authentication
     * challenge is completed in order to unlock an otherwise locked device.
     */
    val authenticationMethod: StateFlow<AuthenticationMethodModel> =
        authenticationInteractor.authenticationMethod

    /** The current authentication throttling state. If `null`, there's no throttling. */
    val throttling: StateFlow<AuthenticationThrottledModel?> = repository.throttling

    init {
        applicationScope.launch {
            combine(
                    sceneInteractor.currentScene(containerName),
                    authenticationInteractor.authenticationMethod,
                    ::Pair,
                )
                .collect { (currentScene, authMethod) ->
                    if (currentScene.key == SceneKey.Bouncer) {
                        when (authMethod) {
                            is AuthenticationMethodModel.None ->
                                sceneInteractor.setCurrentScene(
                                    containerName,
                                    SceneModel(SceneKey.Gone),
                                )
                            is AuthenticationMethodModel.Swipe ->
                                sceneInteractor.setCurrentScene(
                                    containerName,
                                    SceneModel(SceneKey.Lockscreen),
                                )
                            else -> Unit
                        }
                    }
                }
        }
    }

    /**
     * Either shows the bouncer or unlocks the device, if the bouncer doesn't need to be shown.
     *
     * @param containerName The name of the scene container to show the bouncer in.
     * @param message An optional message to show to the user in the bouncer.
     */
    fun showOrUnlockDevice(
        containerName: String,
        message: String? = null,
    ) {
        if (authenticationInteractor.isAuthenticationRequired()) {
            repository.setMessage(message ?: promptMessage(authenticationMethod.value))
            sceneInteractor.setCurrentScene(
                containerName = containerName,
                scene = SceneModel(SceneKey.Bouncer),
            )
        } else {
            authenticationInteractor.unlockDevice()
            sceneInteractor.setCurrentScene(
                containerName = containerName,
                scene = SceneModel(SceneKey.Gone),
            )
        }
    }

    /**
     * Resets the user-facing message back to the default according to the current authentication
     * method.
     */
    fun resetMessage() {
        repository.setMessage(promptMessage(authenticationMethod.value))
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
     * @param input The input from the user to try to authenticate with. This can be a list of
     *   different things, based on the current authentication method.
     * @return `true` if the authentication succeeded and the device is now unlocked; `false`
     *   otherwise.
     */
    fun authenticate(
        input: List<Any>,
    ): Boolean {
        if (repository.throttling.value != null) {
            return false
        }

        val isAuthenticated = authenticationInteractor.authenticate(input)
        val failedAttempts = authenticationInteractor.failedAuthenticationAttempts.value
        when {
            isAuthenticated -> {
                repository.setThrottling(null)
                sceneInteractor.setCurrentScene(
                    containerName = containerName,
                    scene = SceneModel(SceneKey.Gone),
                )
            }
            failedAttempts >= THROTTLE_AGGRESSIVELY_AFTER || failedAttempts % THROTTLE_EVERY == 0 ->
                applicationScope.launch {
                    var remainingDurationSec = THROTTLE_DURATION_SEC
                    while (remainingDurationSec > 0) {
                        repository.setThrottling(
                            AuthenticationThrottledModel(
                                failedAttemptCount = failedAttempts,
                                totalDurationSec = THROTTLE_DURATION_SEC,
                                remainingDurationSec = remainingDurationSec,
                            )
                        )
                        remainingDurationSec--
                        delay(1000)
                    }

                    repository.setThrottling(null)
                    clearMessage()
                }
            else -> repository.setMessage(errorMessage(authenticationMethod.value))
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
        throttling: AuthenticationThrottledModel?,
    ): String {
        return when {
            throttling != null ->
                applicationContext.getString(
                    com.android.internal.R.string.lockscreen_too_many_failed_attempts_countdown,
                    throttling.remainingDurationSec,
                )
            message != null -> message
            else -> ""
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(
            containerName: String,
        ): BouncerInteractor
    }

    companion object {
        @VisibleForTesting const val THROTTLE_DURATION_SEC = 30
        @VisibleForTesting const val THROTTLE_AGGRESSIVELY_AFTER = 15
        @VisibleForTesting const val THROTTLE_EVERY = 5
    }
}
