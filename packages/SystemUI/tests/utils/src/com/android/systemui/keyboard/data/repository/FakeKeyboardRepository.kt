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
 *
 */

package com.android.systemui.keyboard.data.repository

import com.android.systemui.keyboard.data.model.Keyboard
import com.android.systemui.keyboard.shared.model.BacklightModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.filterNotNull

class FakeKeyboardRepository : KeyboardRepository {

    private val _isAnyKeyboardConnected = MutableStateFlow(false)
    override val isAnyKeyboardConnected: Flow<Boolean> = _isAnyKeyboardConnected

    private val _backlightState: MutableStateFlow<BacklightModel?> = MutableStateFlow(null)
    // filtering to make sure backlight doesn't have default initial value
    override val backlight: Flow<BacklightModel> = _backlightState.filterNotNull()

    // implemented as channel because original implementation is modeling events: it doesn't hold
    // state so it won't always emit once connected. And it's bad if some tests depend on that
    // incorrect behaviour.
    private val _newlyConnectedKeyboard: Channel<Keyboard> = Channel()
    override val newlyConnectedKeyboard: Flow<Keyboard> = _newlyConnectedKeyboard.consumeAsFlow()

    private val _connectedKeyboards: MutableStateFlow<Set<Keyboard>> = MutableStateFlow(setOf())
    override val connectedKeyboards: Flow<Set<Keyboard>> = _connectedKeyboards

    fun setBacklight(state: BacklightModel) {
        _backlightState.value = state
    }

    fun setIsAnyKeyboardConnected(connected: Boolean) {
        _isAnyKeyboardConnected.value = connected
    }

    fun setConnectedKeyboards(keyboards: Set<Keyboard>) {
        _connectedKeyboards.value = keyboards
        _isAnyKeyboardConnected.value = keyboards.isNotEmpty()
    }

    fun setNewlyConnectedKeyboard(keyboard: Keyboard) {
        _newlyConnectedKeyboard.trySend(keyboard)
        _connectedKeyboards.value += keyboard
        _isAnyKeyboardConnected.value = true
    }
}
