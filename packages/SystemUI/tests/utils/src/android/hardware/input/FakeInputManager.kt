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

package android.hardware.input

import android.view.InputDevice
import android.view.KeyCharacterMap
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.invocation.InvocationOnMock

class FakeInputManager {

    private val devices = mutableMapOf<Int, InputDevice>()

    val inputManager =
        mock<InputManager> {
            whenever(getInputDevice(anyInt())).thenAnswer { invocation ->
                val deviceId = invocation.arguments[0] as Int
                return@thenAnswer devices[deviceId]
            }
            whenever(inputDeviceIds).thenAnswer {
                return@thenAnswer devices.keys.toIntArray()
            }

            fun setDeviceEnabled(invocation: InvocationOnMock, enabled: Boolean) {
                val deviceId = invocation.arguments[0] as Int
                val device = devices[deviceId] ?: return
                devices[deviceId] = device.copy(enabled = enabled)
            }

            whenever(disableInputDevice(anyInt())).thenAnswer { invocation ->
                setDeviceEnabled(invocation, enabled = false)
            }
            whenever(enableInputDevice(anyInt())).thenAnswer { invocation ->
                setDeviceEnabled(invocation, enabled = true)
            }
        }

    fun addPhysicalKeyboard(id: Int, enabled: Boolean = true) {
        check(id > 0) { "Physical keyboard ids have to be > 0" }
        addKeyboard(id, enabled)
    }

    fun addVirtualKeyboard(enabled: Boolean = true) {
        addKeyboard(id = KeyCharacterMap.VIRTUAL_KEYBOARD, enabled)
    }

    private fun addKeyboard(id: Int, enabled: Boolean = true) {
        devices[id] =
            InputDevice.Builder()
                .setId(id)
                .setKeyboardType(InputDevice.KEYBOARD_TYPE_ALPHABETIC)
                .setSources(InputDevice.SOURCE_KEYBOARD)
                .setEnabled(enabled)
                .build()
    }

    private fun InputDevice.copy(
        id: Int = getId(),
        type: Int = keyboardType,
        sources: Int = getSources(),
        enabled: Boolean = isEnabled
    ) =
        InputDevice.Builder()
            .setId(id)
            .setKeyboardType(type)
            .setSources(sources)
            .setEnabled(enabled)
            .build()
}
