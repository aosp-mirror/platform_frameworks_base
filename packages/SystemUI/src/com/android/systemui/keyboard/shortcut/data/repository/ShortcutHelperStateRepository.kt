/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.keyboard.shortcut.data.repository

import android.hardware.input.InputManager
import android.view.KeyCharacterMap.VIRTUAL_KEYBOARD
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutHelperState
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutHelperState.Active
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutHelperState.Inactive
import com.android.systemui.shared.hardware.findInputDevice
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject

@SysUISingleton
class ShortcutHelperStateRepository
@Inject
constructor(
    private val inputManager: InputManager,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
) {
    private val _state = MutableStateFlow<ShortcutHelperState>(Inactive)
    val state = _state.asStateFlow()

    suspend fun toggle(deviceId: Int? = null) {
        if (_state.value is Inactive) {
            show(deviceId)
        } else {
            hide()
        }
    }

    suspend fun show(deviceId: Int? = null) {
        _state.value = Active(deviceId ?: findPhysicalKeyboardId())
    }

    fun hide() {
        _state.value = Inactive
    }

    private suspend fun findPhysicalKeyboardId() =
        withContext(backgroundDispatcher) {
            val firstEnabledPhysicalKeyboard =
                inputManager.findInputDevice { it.isEnabled && it.isFullKeyboard && !it.isVirtual }
            return@withContext firstEnabledPhysicalKeyboard?.id ?: VIRTUAL_KEYBOARD
        }

}
