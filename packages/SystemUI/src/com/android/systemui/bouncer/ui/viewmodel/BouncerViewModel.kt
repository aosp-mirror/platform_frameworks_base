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

import android.content.Context
import com.android.systemui.R
import com.android.systemui.authentication.domain.interactor.AuthenticationInteractor
import com.android.systemui.authentication.domain.model.AuthenticationMethodModel
import com.android.systemui.bouncer.domain.interactor.BouncerInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import javax.inject.Inject
import kotlin.math.ceil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Holds UI state and handles user input on bouncer UIs. */
@SysUISingleton
class BouncerViewModel
@Inject
constructor(
    @Application private val applicationContext: Context,
    @Application private val applicationScope: CoroutineScope,
    private val bouncerInteractor: BouncerInteractor,
    private val authenticationInteractor: AuthenticationInteractor,
    featureFlags: FeatureFlags,
) {
    private val isInputEnabled: StateFlow<Boolean> =
        bouncerInteractor.isThrottled
            .map { !it }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = !bouncerInteractor.isThrottled.value,
            )

    private val pin: PinBouncerViewModel by lazy {
        PinBouncerViewModel(
            applicationContext = applicationContext,
            applicationScope = applicationScope,
            interactor = bouncerInteractor,
            isInputEnabled = isInputEnabled,
        )
    }

    private val password: PasswordBouncerViewModel by lazy {
        PasswordBouncerViewModel(
            applicationScope = applicationScope,
            interactor = bouncerInteractor,
            isInputEnabled = isInputEnabled,
        )
    }

    private val pattern: PatternBouncerViewModel by lazy {
        PatternBouncerViewModel(
            applicationContext = applicationContext,
            applicationScope = applicationScope,
            interactor = bouncerInteractor,
            isInputEnabled = isInputEnabled,
        )
    }

    /** View-model for the current UI, based on the current authentication method. */
    val authMethod: StateFlow<AuthMethodBouncerViewModel?> =
        authenticationInteractor.authenticationMethod
            .map { authenticationMethod ->
                when (authenticationMethod) {
                    is AuthenticationMethodModel.Pin -> pin
                    is AuthenticationMethodModel.Password -> password
                    is AuthenticationMethodModel.Pattern -> pattern
                    else -> null
                }
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = null,
            )

    init {
        if (featureFlags.isEnabled(Flags.SCENE_CONTAINER)) {
            applicationScope.launch {
                bouncerInteractor.isThrottled
                    .map { isThrottled ->
                        if (isThrottled) {
                            when (authenticationInteractor.getAuthenticationMethod()) {
                                is AuthenticationMethodModel.Pin ->
                                    R.string.kg_too_many_failed_pin_attempts_dialog_message
                                is AuthenticationMethodModel.Password ->
                                    R.string.kg_too_many_failed_password_attempts_dialog_message
                                is AuthenticationMethodModel.Pattern ->
                                    R.string.kg_too_many_failed_pattern_attempts_dialog_message
                                else -> null
                            }?.let { stringResourceId ->
                                applicationContext.getString(
                                    stringResourceId,
                                    bouncerInteractor.throttling.value.failedAttemptCount,
                                    ceil(bouncerInteractor.throttling.value.remainingMs / 1000f)
                                        .toInt(),
                                )
                            }
                        } else {
                            null
                        }
                    }
                    .distinctUntilChanged()
                    .collect { dialogMessageOrNull ->
                        if (dialogMessageOrNull != null) {
                            _throttlingDialogMessage.value = dialogMessageOrNull
                        }
                    }
            }
        }
    }

    /** The user-facing message to show in the bouncer. */
    val message: StateFlow<MessageViewModel> =
        combine(
                bouncerInteractor.message,
                bouncerInteractor.isThrottled,
            ) { message, isThrottled ->
                toMessageViewModel(message, isThrottled)
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue =
                    toMessageViewModel(
                        message = bouncerInteractor.message.value,
                        isThrottled = bouncerInteractor.isThrottled.value,
                    ),
            )

    private val _throttlingDialogMessage = MutableStateFlow<String?>(null)
    /**
     * A message for a throttling dialog to show when the user has attempted the wrong credential
     * too many times and now must wait a while before attempting again.
     *
     * If `null`, no dialog should be shown.
     *
     * Once the dialog is shown, the UI should call [onThrottlingDialogDismissed] when the user
     * dismisses this dialog.
     */
    val throttlingDialogMessage: StateFlow<String?> = _throttlingDialogMessage.asStateFlow()

    /** Notifies that the emergency services button was clicked. */
    fun onEmergencyServicesButtonClicked() {
        // TODO(b/280877228): implement this
    }

    /** Notifies that a throttling dialog has been dismissed by the user. */
    fun onThrottlingDialogDismissed() {
        _throttlingDialogMessage.value = null
    }

    private fun toMessageViewModel(
        message: String?,
        isThrottled: Boolean,
    ): MessageViewModel {
        return MessageViewModel(
            text = message ?: "",
            isUpdateAnimated = !isThrottled,
        )
    }

    data class MessageViewModel(
        val text: String,

        /**
         * Whether updates to the message should be cross-animated from one message to another.
         *
         * If `false`, no animation should be applied, the message text should just be replaced
         * instantly.
         */
        val isUpdateAnimated: Boolean,
    )
}
