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

import com.android.systemui.bouncer.domain.interactor.BouncerInteractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
        if (!interactor.authenticate(mutablePinEntries.value.map { it.input })) {
            showFailureAnimation()
        }

        mutablePinEntries.value = emptyList()
    }
}

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
