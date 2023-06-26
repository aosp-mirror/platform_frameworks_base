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
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.bouncer.domain.interactor.BouncerInteractor
import com.android.systemui.bouncer.shared.model.AuthenticationThrottledModel
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.util.kotlin.pairwise
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
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
class BouncerViewModel
@AssistedInject
constructor(
    @Application private val applicationContext: Context,
    @Application private val applicationScope: CoroutineScope,
    interactorFactory: BouncerInteractor.Factory,
    @Assisted containerName: String,
) {
    private val interactor: BouncerInteractor = interactorFactory.create(containerName)

    private val isInputEnabled: StateFlow<Boolean> =
        interactor.throttling
            .map { it == null }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = interactor.throttling.value == null,
            )

    private val pin: PinBouncerViewModel by lazy {
        PinBouncerViewModel(
            applicationScope = applicationScope,
            interactor = interactor,
            isInputEnabled = isInputEnabled,
        )
    }

    private val password: PasswordBouncerViewModel by lazy {
        PasswordBouncerViewModel(
            applicationScope = applicationScope,
            interactor = interactor,
            isInputEnabled = isInputEnabled,
        )
    }

    private val pattern: PatternBouncerViewModel by lazy {
        PatternBouncerViewModel(
            applicationContext = applicationContext,
            applicationScope = applicationScope,
            interactor = interactor,
            isInputEnabled = isInputEnabled,
        )
    }

    /** View-model for the current UI, based on the current authentication method. */
    private val _authMethod =
        MutableSharedFlow<AuthMethodBouncerViewModel?>(
            replay = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val authMethod: StateFlow<AuthMethodBouncerViewModel?> =
        _authMethod.stateIn(
            scope = applicationScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = null,
        )

    init {
        applicationScope.launch {
            _authMethod.subscriptionCount
                .pairwise()
                .map { (previousCount, currentCount) -> currentCount > previousCount }
                .collect { subscriberAdded ->
                    if (subscriberAdded) {
                        reloadAuthMethod()
                    }
                }
        }
    }

    /** The user-facing message to show in the bouncer. */
    val message: StateFlow<MessageViewModel> =
        combine(
                interactor.message,
                interactor.throttling,
            ) { message, throttling ->
                toMessageViewModel(message, throttling)
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue =
                    toMessageViewModel(
                        message = interactor.message.value,
                        throttling = interactor.throttling.value,
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

    init {
        applicationScope.launch {
            interactor.throttling
                .map { model ->
                    model?.let {
                        when (interactor.getAuthenticationMethod()) {
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
                                model.failedAttemptCount,
                                model.totalDurationSec,
                            )
                        }
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
        throttling: AuthenticationThrottledModel?,
    ): MessageViewModel {
        return MessageViewModel(
            text = message ?: "",
            isUpdateAnimated = throttling == null,
        )
    }

    private suspend fun reloadAuthMethod() {
        _authMethod.tryEmit(
            when (interactor.getAuthenticationMethod()) {
                is AuthenticationMethodModel.Pin -> pin
                is AuthenticationMethodModel.Password -> password
                is AuthenticationMethodModel.Pattern -> pattern
                else -> null
            }
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

    @AssistedFactory
    interface Factory {
        fun create(
            containerName: String,
        ): BouncerViewModel
    }
}
