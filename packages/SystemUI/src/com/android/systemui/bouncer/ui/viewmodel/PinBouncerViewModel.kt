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
import com.android.keyguard.PinShapeAdapter
import com.android.systemui.bouncer.domain.interactor.BouncerInteractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Holds UI state and handles user input for the PIN code bouncer UI. */
class PinBouncerViewModel(
    applicationContext: Context,
    private val applicationScope: CoroutineScope,
    private val interactor: BouncerInteractor,
    isInputEnabled: StateFlow<Boolean>,
) :
    AuthMethodBouncerViewModel(
        isInputEnabled = isInputEnabled,
    ) {

    val pinShapes = PinShapeAdapter(applicationContext)
    private val mutablePinInput = MutableStateFlow(PinInputViewModel.empty())

    /** Currently entered pin keys. */
    val pinInput: StateFlow<PinInputViewModel> = mutablePinInput

    /** The length of the PIN for which we should show a hint. */
    val hintedPinLength: StateFlow<Int?> = interactor.hintedPinLength

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
                scope = applicationScope,
                // Make sure this is kept as WhileSubscribed or we can run into a bug where the
                // downstream continues to receive old/stale/cached values.
                started = SharingStarted.WhileSubscribed(),
                initialValue = ActionButtonAppearance.Hidden,
            )

    /** Appearance of the confirm button. */
    val confirmButtonAppearance: StateFlow<ActionButtonAppearance> =
        interactor.isAutoConfirmEnabled
            .map {
                if (it) {
                    ActionButtonAppearance.Hidden
                } else {
                    ActionButtonAppearance.Shown
                }
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = ActionButtonAppearance.Hidden,
            )

    /** Notifies that the UI has been shown to the user. */
    fun onShown() {
        interactor.resetMessage()
    }

    /** Notifies that the user has placed down a pointer. */
    fun onDown() {
        interactor.onDown()
    }

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
        mutablePinInput.value = mutablePinInput.value.clearAll()
    }

    /** Notifies that the user clicked the "enter" button. */
    fun onAuthenticateButtonClicked() {
        tryAuthenticate(useAutoConfirm = false)
    }

    private fun tryAuthenticate(useAutoConfirm: Boolean) {
        val pinCode = mutablePinInput.value.getPin()

        applicationScope.launch {
            val isSuccess = interactor.authenticate(pinCode, useAutoConfirm) ?: return@launch

            if (!isSuccess) {
                showFailureAnimation()
            }

            // TODO(b/291528545): this should not be cleared on success (at least until the view
            // is animated away).
            mutablePinInput.value = mutablePinInput.value.clearAll()
        }
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
