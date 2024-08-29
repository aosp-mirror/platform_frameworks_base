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

import android.hardware.input.InputManager
import android.hardware.input.InputManager.KeyboardBacklightListener
import android.hardware.input.KeyboardBacklightState
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.inputdevice.data.repository.InputDeviceRepository
import com.android.systemui.inputdevice.data.repository.InputDeviceRepository.DeviceAdded
import com.android.systemui.inputdevice.data.repository.InputDeviceRepository.DeviceRemoved
import com.android.systemui.inputdevice.data.repository.InputDeviceRepository.FreshStart
import com.android.systemui.keyboard.data.model.Keyboard
import com.android.systemui.keyboard.shared.model.BacklightModel
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull

/**
 * Provides information about physical keyboard states. [CommandLineKeyboardRepository] can be
 * useful command line-driven implementation during development.
 */
interface KeyboardRepository {
    /** Emits true if any physical keyboard is connected to the device, false otherwise. */
    val isAnyKeyboardConnected: Flow<Boolean>

    /**
     * Emits [Keyboard] object whenever new physical keyboard connects. When SysUI (re)starts it
     * emits all currently connected keyboards
     */
    val newlyConnectedKeyboard: Flow<Keyboard>

    /**
     * Emits [BacklightModel] whenever user changes backlight level from keyboard press. Can only
     * happen when physical keyboard is connected
     */
    val backlight: Flow<BacklightModel>
}

@SysUISingleton
class KeyboardRepositoryImpl
@Inject
constructor(
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val inputManager: InputManager,
    inputDeviceRepository: InputDeviceRepository
) : KeyboardRepository {

    @FlowPreview
    override val newlyConnectedKeyboard: Flow<Keyboard> =
        inputDeviceRepository.deviceChange
            .flatMapConcat { (devices, operation) ->
                when (operation) {
                    FreshStart -> devices.filter { id -> isPhysicalFullKeyboard(id) }.asFlow()
                    is DeviceAdded -> {
                        if (isPhysicalFullKeyboard(operation.deviceId)) flowOf(operation.deviceId)
                        else emptyFlow()
                    }
                    is DeviceRemoved -> emptyFlow()
                }
            }
            .mapNotNull { deviceIdToKeyboard(it) }
            .flowOn(backgroundDispatcher)

    override val isAnyKeyboardConnected: Flow<Boolean> =
        inputDeviceRepository.deviceChange
            .map { (ids, _) -> ids.any { id -> isPhysicalFullKeyboard(id) } }
            .distinctUntilChanged()
            .flowOn(backgroundDispatcher)

    private val backlightStateListener: Flow<KeyboardBacklightState> = conflatedCallbackFlow {
        val listener = KeyboardBacklightListener { _, state, isTriggeredByKeyPress ->
            if (isTriggeredByKeyPress) {
                sendWithLogging(state)
            }
        }
        inputManager.registerKeyboardBacklightListener(Executor(Runnable::run), listener)
        awaitClose { inputManager.unregisterKeyboardBacklightListener(listener) }
    }

    private fun deviceIdToKeyboard(deviceId: Int): Keyboard? {
        val device = inputManager.getInputDevice(deviceId) ?: return null
        return Keyboard(device.vendorId, device.productId)
    }

    override val backlight: Flow<BacklightModel> =
        backlightStateListener
            .map { BacklightModel(it.brightnessLevel, it.maxBrightnessLevel) }
            .flowOn(backgroundDispatcher)

    private fun <T> SendChannel<T>.sendWithLogging(element: T) {
        trySendWithFailureLogging(element, TAG)
    }

    private fun isPhysicalFullKeyboard(deviceId: Int): Boolean {
        val device = inputManager.getInputDevice(deviceId) ?: return false
        return !device.isVirtual && device.isFullKeyboard
    }

    companion object {
        const val TAG = "KeyboardRepositoryImpl"
    }
}
