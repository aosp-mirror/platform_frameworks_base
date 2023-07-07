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

import androidx.annotation.VisibleForTesting
import com.android.systemui.bouncer.domain.interactor.BouncerInteractor
import com.android.systemui.util.kotlin.pairwise
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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

    private val entered = MutableStateFlow<List<Int>>(emptyList())
    /**
     * The length of the PIN digits that were input so far, two values are supplied the previous and
     * the current.
     */
    val pinLengths: StateFlow<Pair<Int, Int>> =
        entered
            .pairwise()
            .map { it.previousValue.size to it.newValue.size }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = 0 to 0,
            )
    private var resetPinJob: Job? = null

    /** Notifies that the UI has been shown to the user. */
    fun onShown() {
        interactor.resetMessage()
    }

    /** Notifies that the user clicked on a PIN button with the given digit value. */
    fun onPinButtonClicked(input: Int) {
        resetPinJob?.cancel()
        resetPinJob = null

        if (entered.value.isEmpty()) {
            interactor.clearMessage()
        }

        entered.value += input
    }

    /** Notifies that the user clicked the backspace button. */
    fun onBackspaceButtonClicked() {
        if (entered.value.isEmpty()) {
            return
        }

        entered.value = entered.value.toMutableList().apply { removeLast() }
    }

    /** Notifies that the user long-pressed the backspace button. */
    fun onBackspaceButtonLongPressed() {
        resetPinJob?.cancel()
        resetPinJob =
            applicationScope.launch {
                while (entered.value.isNotEmpty()) {
                    onBackspaceButtonClicked()
                    delay(BACKSPACE_LONG_PRESS_DELAY_MS)
                }
            }
    }

    /** Notifies that the user clicked the "enter" button. */
    fun onAuthenticateButtonClicked() {
        if (!interactor.authenticate(entered.value)) {
            showFailureAnimation()
        }

        entered.value = emptyList()
    }

    companion object {
        @VisibleForTesting const val BACKSPACE_LONG_PRESS_DELAY_MS = 80L
    }
}
