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

import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.bouncer.domain.interactor.BouncerInteractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Holds UI state and handles user input for the PIN code bouncer UI. */
class PinBouncerViewModel(
    private val applicationScope: CoroutineScope,
    private val interactor: BouncerInteractor,
    isInputEnabled: StateFlow<Boolean>,
) :
    AuthMethodBouncerViewModel(
        isInputEnabled = isInputEnabled,
    ) {

    private val mutablePinEntries = MutableStateFlow<List<EnteredKey>>(emptyList())
    val pinEntries: StateFlow<List<EnteredKey>> = mutablePinEntries

    /** The length of the hinted PIN, or `null` if pin length hint should not be shown. */
    val hintedPinLength: StateFlow<Int?> =
        flow { emit(interactor.getAuthenticationMethod()) }
            .map { authMethod ->
                // Hinting is enabled for 6-digit codes only
                autoConfirmPinLength(authMethod).takeIf { it == HINTING_PASSCODE_LENGTH }
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = null,
            )

    /** Appearance of the backspace button. */
    val backspaceButtonAppearance: StateFlow<ActionButtonAppearance> =
        mutablePinEntries
            .map { mutablePinEntries ->
                computeBackspaceButtonAppearance(
                    interactor.getAuthenticationMethod(),
                    mutablePinEntries
                )
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = ActionButtonAppearance.Hidden,
            )

    /** Appearance of the confirm button. */
    val confirmButtonAppearance: StateFlow<ActionButtonAppearance> =
        flow {
                emit(null)
                emit(interactor.getAuthenticationMethod())
            }
            .map { authMethod -> computeConfirmButtonAppearance(authMethod) }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = ActionButtonAppearance.Hidden,
            )

    /** Notifies that the UI has been shown to the user. */
    fun onShown() {
        interactor.resetMessage()
    }

    /** Notifies that the user clicked on a PIN button with the given digit value. */
    fun onPinButtonClicked(input: Int) {
        if (mutablePinEntries.value.isEmpty()) {
            interactor.clearMessage()
        }

        mutablePinEntries.value += EnteredKey(input)

        tryAuthenticate(useAutoConfirm = true)
    }

    /** Notifies that the user clicked the backspace button. */
    fun onBackspaceButtonClicked() {
        if (mutablePinEntries.value.isEmpty()) {
            return
        }
        mutablePinEntries.value = mutablePinEntries.value.toMutableList().apply { removeLast() }
    }

    /** Notifies that the user long-pressed the backspace button. */
    fun onBackspaceButtonLongPressed() {
        mutablePinEntries.value = emptyList()
    }

    /** Notifies that the user clicked the "enter" button. */
    fun onAuthenticateButtonClicked() {
        tryAuthenticate(useAutoConfirm = false)
    }

    private fun tryAuthenticate(useAutoConfirm: Boolean) {
        val pinCode = mutablePinEntries.value.map { it.input }

        applicationScope.launch {
            val isSuccess = interactor.authenticate(pinCode, useAutoConfirm) ?: return@launch

            if (!isSuccess) {
                showFailureAnimation()
            }

            mutablePinEntries.value = emptyList()
        }
    }

    private fun isAutoConfirmEnabled(authMethodModel: AuthenticationMethodModel?): Boolean {
        return (authMethodModel as? AuthenticationMethodModel.Pin)?.autoConfirm == true
    }

    private fun autoConfirmPinLength(authMethodModel: AuthenticationMethodModel?): Int? {
        if (!isAutoConfirmEnabled(authMethodModel)) return null

        return (authMethodModel as? AuthenticationMethodModel.Pin)?.code?.size
    }

    private fun computeBackspaceButtonAppearance(
        authMethodModel: AuthenticationMethodModel,
        enteredPin: List<EnteredKey>
    ): ActionButtonAppearance {
        val isAutoConfirmEnabled = isAutoConfirmEnabled(authMethodModel)
        val isEmpty = enteredPin.isEmpty()

        return when {
            isAutoConfirmEnabled && isEmpty -> ActionButtonAppearance.Hidden
            isAutoConfirmEnabled -> ActionButtonAppearance.Subtle
            else -> ActionButtonAppearance.Shown
        }
    }
    private fun computeConfirmButtonAppearance(
        authMethodModel: AuthenticationMethodModel?
    ): ActionButtonAppearance {
        return if (isAutoConfirmEnabled(authMethodModel)) {
            ActionButtonAppearance.Hidden
        } else {
            ActionButtonAppearance.Shown
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

/** Auto-confirm passcodes of exactly 6 digits show a length hint, see http://shortn/_IXlmSNbDh6 */
private const val HINTING_PASSCODE_LENGTH = 6

private var nextSequenceNumber = 1

/**
 * The pin bouncer [input] as digits 0-9, together with a [sequenceNumber] to indicate the ordering.
 *
 * Since the model only allows appending/removing [EnteredKey]s from the end, the [sequenceNumber]
 * is strictly increasing in input order of the pin, but not guaranteed to be monotonic or start at
 * a specific number.
 */
data class EnteredKey
internal constructor(val input: Int, val sequenceNumber: Int = nextSequenceNumber++) :
    Comparable<EnteredKey> {
    override fun compareTo(other: EnteredKey): Int =
        compareValuesBy(this, other, EnteredKey::sequenceNumber)
}
