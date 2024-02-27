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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.bouncer.ui.viewmodel

import android.content.Context
import com.android.keyguard.PinShapeAdapter
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.bouncer.domain.interactor.BouncerInteractor
import com.android.systemui.bouncer.domain.interactor.SimBouncerInteractor
import com.android.systemui.res.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Holds UI state and handles user input for the PIN code bouncer UI. */
class PinBouncerViewModel(
    applicationContext: Context,
    viewModelScope: CoroutineScope,
    interactor: BouncerInteractor,
    isInputEnabled: StateFlow<Boolean>,
    private val simBouncerInteractor: SimBouncerInteractor,
    authenticationMethod: AuthenticationMethodModel,
) :
    AuthMethodBouncerViewModel(
        viewModelScope = viewModelScope,
        interactor = interactor,
        isInputEnabled = isInputEnabled,
    ) {
    /**
     * Whether the sim-related UI in the pin view is showing.
     *
     * This UI is used to unlock a locked sim.
     */
    val isSimAreaVisible = authenticationMethod == AuthenticationMethodModel.Sim
    val isLockedEsim: StateFlow<Boolean?> = simBouncerInteractor.isLockedEsim
    val errorDialogMessage: StateFlow<String?> = simBouncerInteractor.errorDialogMessage
    val isSimUnlockingDialogVisible: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val pinShapes = PinShapeAdapter(applicationContext)
    private val mutablePinInput = MutableStateFlow(PinInputViewModel.empty())

    /** Currently entered pin keys. */
    val pinInput: StateFlow<PinInputViewModel> = mutablePinInput

    /** The length of the PIN for which we should show a hint. */
    val hintedPinLength: StateFlow<Int?> =
        if (isSimAreaVisible) {
                flowOf(null)
            } else {
                interactor.hintedPinLength
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    /** Appearance of the backspace button. */
    val backspaceButtonAppearance: StateFlow<ActionButtonAppearance> =
        combine(
                mutablePinInput,
                interactor.isAutoConfirmEnabled,
            ) { mutablePinEntries, isAutoConfirmEnabled ->
                computeBackspaceButtonAppearance(
                    pinInput = mutablePinEntries,
                    isAutoConfirmEnabled = isAutoConfirmEnabled,
                )
            }
            .stateIn(
                scope = viewModelScope,
                // Make sure this is kept as WhileSubscribed or we can run into a bug where the
                // downstream continues to receive old/stale/cached values.
                started = SharingStarted.WhileSubscribed(),
                initialValue = ActionButtonAppearance.Hidden,
            )

    /** Appearance of the confirm button. */
    val confirmButtonAppearance: StateFlow<ActionButtonAppearance> =
        interactor.isAutoConfirmEnabled
            .map { if (it) ActionButtonAppearance.Hidden else ActionButtonAppearance.Shown }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = ActionButtonAppearance.Hidden,
            )

    override val authenticationMethod: AuthenticationMethodModel = authenticationMethod

    override val lockoutMessageId = R.string.kg_too_many_failed_pin_attempts_dialog_message

    init {
        viewModelScope.launch { simBouncerInteractor.subId.collect { onResetSimFlow() } }
    }

    /** Notifies that the user dismissed the sim pin error dialog. */
    fun onErrorDialogDismissed() {
        viewModelScope.launch { simBouncerInteractor.onErrorDialogDismissed() }
    }

    /**
     * Whether the digit buttons should be animated when touched. Note that this doesn't affect the
     * delete or enter buttons; those should always animate.
     */
    val isDigitButtonAnimationEnabled: StateFlow<Boolean> =
        interactor.isPinEnhancedPrivacyEnabled
            .map { !it }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = !interactor.isPinEnhancedPrivacyEnabled.value,
            )

    /** Notifies that the user clicked on a PIN button with the given digit value. */
    fun onPinButtonClicked(input: Int) {
        val pinInput = mutablePinInput.value
        if (pinInput.isEmpty()) {
            interactor.clearMessage()
        }

        interactor.onIntentionalUserInput()

        mutablePinInput.value = pinInput.append(input)
        tryAuthenticate(useAutoConfirm = true)
    }

    /** Notifies that the user clicked the backspace button. */
    fun onBackspaceButtonClicked() {
        mutablePinInput.value = mutablePinInput.value.deleteLast()
    }

    /** Notifies that the user long-pressed the backspace button. */
    fun onBackspaceButtonLongPressed() {
        clearInput()
        interactor.clearMessage()
    }

    /** Notifies that the user clicked the "enter" button. */
    fun onAuthenticateButtonClicked() {
        if (authenticationMethod == AuthenticationMethodModel.Sim) {
            viewModelScope.launch {
                isSimUnlockingDialogVisible.value = true
                simBouncerInteractor.verifySim(getInput())
                isSimUnlockingDialogVisible.value = false
                clearInput()
            }
        } else {
            tryAuthenticate(useAutoConfirm = false)
        }
    }

    fun onDisableEsimButtonClicked() {
        viewModelScope.launch { simBouncerInteractor.disableEsim() }
    }

    /** Resets the sim screen and shows a default message. */
    private fun onResetSimFlow() {
        simBouncerInteractor.resetSimPukUserInput()
        interactor.resetMessage()
        clearInput()
    }

    override fun clearInput() {
        mutablePinInput.value = mutablePinInput.value.clearAll()
    }

    override fun getInput(): List<Any> {
        return mutablePinInput.value.getPin()
    }

    private fun computeBackspaceButtonAppearance(
        pinInput: PinInputViewModel,
        isAutoConfirmEnabled: Boolean,
    ): ActionButtonAppearance {
        val isEmpty = pinInput.isEmpty()

        return when {
            isAutoConfirmEnabled && isEmpty -> ActionButtonAppearance.Hidden
            isAutoConfirmEnabled -> ActionButtonAppearance.Subtle
            else -> ActionButtonAppearance.Shown
        }
    }
}

/** Appearance of pin-pad action buttons. */
enum class ActionButtonAppearance {
    /** Button must not be shown. */
    Hidden,

    /** Button is shown, but with no background to make it less prominent. */
    Subtle,

    /** Button is shown. */
    Shown,
}
