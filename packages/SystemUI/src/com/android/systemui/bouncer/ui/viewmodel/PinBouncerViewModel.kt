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
import android.view.HapticFeedbackConstants
import android.view.KeyEvent.KEYCODE_0
import android.view.KeyEvent.KEYCODE_9
import android.view.KeyEvent.KEYCODE_DEL
import android.view.KeyEvent.KEYCODE_NUMPAD_0
import android.view.KeyEvent.KEYCODE_NUMPAD_9
import android.view.KeyEvent.isConfirmKey
import android.view.View
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import com.android.keyguard.PinShapeAdapter
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.bouncer.domain.interactor.BouncerInteractor
import com.android.systemui.bouncer.domain.interactor.SimBouncerInteractor
import com.android.systemui.bouncer.shared.flag.ComposeBouncerFlags
import com.android.systemui.bouncer.ui.helper.BouncerHapticPlayer
import com.android.systemui.res.R
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** Holds UI state and handles user input for the PIN code bouncer UI. */
class PinBouncerViewModel
@AssistedInject
constructor(
    applicationContext: Context,
    interactor: BouncerInteractor,
    private val simBouncerInteractor: SimBouncerInteractor,
    @Assisted bouncerHapticPlayer: BouncerHapticPlayer,
    @Assisted isInputEnabled: StateFlow<Boolean>,
    @Assisted private val onIntentionalUserInput: () -> Unit,
    @Assisted override val authenticationMethod: AuthenticationMethodModel,
) :
    AuthMethodBouncerViewModel(
        interactor = interactor,
        isInputEnabled = isInputEnabled,
        traceName = "PinBouncerViewModel",
        bouncerHapticPlayer = bouncerHapticPlayer,
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

    private val _hintedPinLength = MutableStateFlow<Int?>(null)
    /** The length of the PIN for which we should show a hint. */
    val hintedPinLength: StateFlow<Int?> = _hintedPinLength.asStateFlow()

    private val _backspaceButtonAppearance = MutableStateFlow(ActionButtonAppearance.Hidden)
    /** Appearance of the backspace button. */
    val backspaceButtonAppearance: StateFlow<ActionButtonAppearance> =
        _backspaceButtonAppearance.asStateFlow()

    private val _confirmButtonAppearance = MutableStateFlow(ActionButtonAppearance.Hidden)
    /** Appearance of the confirm button. */
    val confirmButtonAppearance: StateFlow<ActionButtonAppearance> =
        _confirmButtonAppearance.asStateFlow()

    override val lockoutMessageId = R.string.kg_too_many_failed_pin_attempts_dialog_message

    private val requests = Channel<Request>(Channel.BUFFERED)

    override suspend fun onActivated(): Nothing {
        coroutineScope {
            launch { super.onActivated() }
            launch {
                requests.receiveAsFlow().collect { request ->
                    when (request) {
                        is OnErrorDialogDismissed -> {
                            simBouncerInteractor.onErrorDialogDismissed()
                        }
                        is OnAuthenticateButtonClickedForSim -> {
                            isSimUnlockingDialogVisible.value = true
                            simBouncerInteractor.verifySim(getInput())
                            isSimUnlockingDialogVisible.value = false
                            clearInput()
                        }
                    }
                }
            }
            launch { simBouncerInteractor.subId.collect { onResetSimFlow() } }
            launch {
                if (isSimAreaVisible) {
                        flowOf(null)
                    } else {
                        interactor.hintedPinLength
                    }
                    .collect { _hintedPinLength.value = it }
            }
            launch {
                combine(mutablePinInput, interactor.isAutoConfirmEnabled) {
                        mutablePinEntries,
                        isAutoConfirmEnabled ->
                        computeBackspaceButtonAppearance(
                            pinInput = mutablePinEntries,
                            isAutoConfirmEnabled = isAutoConfirmEnabled,
                        )
                    }
                    .collect { _backspaceButtonAppearance.value = it }
            }
            launch {
                interactor.isAutoConfirmEnabled
                    .map { if (it) ActionButtonAppearance.Hidden else ActionButtonAppearance.Shown }
                    .collect { _confirmButtonAppearance.value = it }
            }
            launch {
                interactor.isPinEnhancedPrivacyEnabled
                    .map { !it }
                    .collect { _isDigitButtonAnimationEnabled.value = it }
            }
            awaitCancellation()
        }
    }

    /** Notifies that the user dismissed the sim pin error dialog. */
    fun onErrorDialogDismissed() {
        requests.trySend(OnErrorDialogDismissed)
    }

    private val _isDigitButtonAnimationEnabled =
        MutableStateFlow(!interactor.isPinEnhancedPrivacyEnabled.value)
    /**
     * Whether the digit buttons should be animated when touched. Note that this doesn't affect the
     * delete or enter buttons; those should always animate.
     */
    val isDigitButtonAnimationEnabled: StateFlow<Boolean> =
        _isDigitButtonAnimationEnabled.asStateFlow()

    /** Notifies that the user clicked on a PIN button with the given digit value. */
    fun onPinButtonClicked(input: Int) {
        val pinInput = mutablePinInput.value

        onIntentionalUserInput()

        val maxInputLength = hintedPinLength.value ?: Int.MAX_VALUE
        if (pinInput.getPin().size < maxInputLength) {
            mutablePinInput.value = pinInput.append(input)
            tryAuthenticate(useAutoConfirm = true)
        }
    }

    /** Notifies that the user clicked the backspace button. */
    fun onBackspaceButtonClicked() {
        mutablePinInput.value = mutablePinInput.value.deleteLast()
    }

    fun onBackspaceButtonPressed(view: View?) {
        if (bouncerHapticPlayer?.isEnabled == true) {
            bouncerHapticPlayer.playDeleteKeyPressFeedback()
        } else {
            view?.performHapticFeedback(
                HapticFeedbackConstants.VIRTUAL_KEY,
                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING,
            )
        }
    }

    /** Notifies that the user long-pressed the backspace button. */
    fun onBackspaceButtonLongPressed() {
        if (bouncerHapticPlayer?.isEnabled == true) {
            bouncerHapticPlayer.playDeleteKeyLongPressedFeedback()
        }
        clearInput()
    }

    /** Notifies that the user clicked the "enter" button. */
    fun onAuthenticateButtonClicked() {
        if (authenticationMethod == AuthenticationMethodModel.Sim) {
            requests.trySend(OnAuthenticateButtonClickedForSim)
        } else {
            tryAuthenticate(useAutoConfirm = false)
        }
    }

    fun onDisableEsimButtonClicked() {
        simBouncerInteractor.disableEsim()
    }

    /** Resets the sim screen and shows a default message. */
    private fun onResetSimFlow() {
        simBouncerInteractor.resetSimPukUserInput()
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

    /**
     * Notifies that a key event has occurred.
     *
     * @return `true` when the [KeyEvent] was consumed as user input on bouncer; `false` otherwise.
     */
    fun onKeyEvent(type: KeyEventType, keyCode: Int): Boolean {
        return when (type) {
            KeyEventType.KeyUp -> {
                if (isConfirmKey(keyCode)) {
                    onAuthenticateButtonClicked()
                    true
                } else {
                    false
                }
            }
            KeyEventType.KeyDown -> {
                when (keyCode) {
                    KEYCODE_DEL -> {
                        onBackspaceButtonClicked()
                        true
                    }
                    in KEYCODE_0..KEYCODE_9 -> {
                        onPinButtonClicked(keyCode - KEYCODE_0)
                        true
                    }
                    in KEYCODE_NUMPAD_0..KEYCODE_NUMPAD_9 -> {
                        onPinButtonClicked(keyCode - KEYCODE_NUMPAD_0)
                        true
                    }
                    else -> {
                        false
                    }
                }
            }
            else -> false
        }
    }

    /**
     * Notifies that the user has pressed down on a digit button. This function also performs haptic
     * feedback on the view.
     */
    fun onDigitButtonDown(view: View?) {
        if (ComposeBouncerFlags.isOnlyComposeBouncerEnabled()) {
            // Current PIN bouncer informs FalsingInteractor#avoidGesture() upon every Pin button
            // touch.
            super.onDown()
        }
        if (bouncerHapticPlayer?.isEnabled == true) {
            bouncerHapticPlayer.playNumpadKeyFeedback()
        } else {
            view?.performHapticFeedback(
                HapticFeedbackConstants.VIRTUAL_KEY,
                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING,
            )
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(
            isInputEnabled: StateFlow<Boolean>,
            onIntentionalUserInput: () -> Unit,
            authenticationMethod: AuthenticationMethodModel,
            bouncerHapticPlayer: BouncerHapticPlayer,
        ): PinBouncerViewModel
    }

    private sealed interface Request

    private data object OnErrorDialogDismissed : Request

    private data object OnAuthenticateButtonClickedForSim : Request
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
