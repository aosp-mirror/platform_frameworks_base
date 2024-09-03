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

package com.android.systemui.touchpad.data.repository

import android.hardware.input.InputManager
import android.view.InputDevice.SOURCE_TOUCHPAD
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.inputdevice.data.repository.InputDeviceRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

interface TouchpadRepository {
    /** Emits true if any touchpad is connected to the device, false otherwise. */
    val isAnyTouchpadConnected: Flow<Boolean>
}

@SysUISingleton
class TouchpadRepositoryImpl
@Inject
constructor(
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val inputManager: InputManager,
    inputDeviceRepository: InputDeviceRepository
) : TouchpadRepository {

    override val isAnyTouchpadConnected: Flow<Boolean> =
        inputDeviceRepository.deviceChange
            .map { (ids, _) -> ids.any { id -> isTouchpad(id) } }
            .distinctUntilChanged()
            .flowOn(backgroundDispatcher)

    private fun isTouchpad(deviceId: Int): Boolean {
        val device = inputManager.getInputDevice(deviceId) ?: return false
        return device.supportsSource(SOURCE_TOUCHPAD)
    }

    companion object {
        const val TAG = "TouchpadRepositoryImpl"
    }
}
