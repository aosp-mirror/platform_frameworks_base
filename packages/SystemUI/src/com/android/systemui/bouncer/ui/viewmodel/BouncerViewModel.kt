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
import com.android.systemui.authentication.domain.interactor.AuthenticationInteractor
import com.android.systemui.authentication.domain.model.AuthenticationMethodModel
import com.android.systemui.bouncer.domain.interactor.BouncerInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.scene.shared.flag.SceneContainerFlags
import javax.inject.Inject
import kotlin.math.ceil
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/** Holds UI state and handles user input on bouncer UIs. */
@SysUISingleton
class BouncerViewModel
@Inject
constructor(
    @Application private val applicationContext: Context,
    @Application private val applicationScope: CoroutineScope,
    @Main private val mainDispatcher: CoroutineDispatcher,
    private val bouncerInteractor: BouncerInteractor,
    authenticationInteractor: AuthenticationInteractor,
    flags: SceneContainerFlags,
) {
    private val isInputEnabled: StateFlow<Boolean> =
        bouncerInteractor.isThrottled
            .map { !it }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = !bouncerInteractor.isThrottled.value,
            )

    // Handle to the scope of the child ViewModel (stored in [authMethod]).
    private var childViewModelScope: CoroutineScope? = null
    private val _throttlingDialogMessage = MutableStateFlow<String?>(null)

    /** View-model for the current UI, based on the current authentication method. */
    val authMethodViewModel: StateFlow<AuthMethodBouncerViewModel?> =
        authenticationInteractor.authenticationMethod
            .map(::getChildViewModel)
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = null,
            )

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

    /** The user-facing message to show in the bouncer. */
    val message: StateFlow<MessageViewModel> =
        combine(bouncerInteractor.message, bouncerInteractor.isThrottled) { message, isThrottled ->
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

    init {
        if (flags.isEnabled()) {
            applicationScope.launch {
                combine(bouncerInteractor.isThrottled, authMethodViewModel) {
                        isThrottled,
                        authMethodViewModel ->
                        if (isThrottled && authMethodViewModel != null) {
                            applicationContext.getString(
                                authMethodViewModel.throttlingMessageId,
                                bouncerInteractor.throttling.value.failedAttemptCount,
                                ceil(bouncerInteractor.throttling.value.remainingMs / 1000f)
                                    .toInt(),
                            )
                        } else {
                            null
                        }
                    }
                    .distinctUntilChanged()
                    .collect { dialogMessage -> _throttlingDialogMessage.value = dialogMessage }
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
        isThrottled: Boolean,
    ): MessageViewModel {
        return MessageViewModel(
            text = message ?: "",
            isUpdateAnimated = !isThrottled,
        )
    }

    private fun getChildViewModel(
        authenticationMethod: AuthenticationMethodModel,
    ): AuthMethodBouncerViewModel? {
        // If the current child view-model matches the authentication method, reuse it instead of
        // creating a new instance.
        val childViewModel = authMethodViewModel.value
        if (authenticationMethod == childViewModel?.authenticationMethod) {
            return childViewModel
        }

        childViewModelScope?.cancel()
        val newViewModelScope = createChildCoroutineScope(applicationScope)
        childViewModelScope = newViewModelScope
        return when (authenticationMethod) {
            is AuthenticationMethodModel.Pin ->
                PinBouncerViewModel(
                    applicationContext = applicationContext,
                    viewModelScope = newViewModelScope,
                    interactor = bouncerInteractor,
                    isInputEnabled = isInputEnabled,
                )
            is AuthenticationMethodModel.Password ->
                PasswordBouncerViewModel(
                    viewModelScope = newViewModelScope,
                    interactor = bouncerInteractor,
                    isInputEnabled = isInputEnabled,
                )
            is AuthenticationMethodModel.Pattern ->
                PatternBouncerViewModel(
                    applicationContext = applicationContext,
                    viewModelScope = newViewModelScope,
                    interactor = bouncerInteractor,
                    isInputEnabled = isInputEnabled,
                )
            else -> null
        }
    }

    private fun createChildCoroutineScope(parentScope: CoroutineScope): CoroutineScope {
        return CoroutineScope(
            SupervisorJob(parent = parentScope.coroutineContext.job) + mainDispatcher
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
