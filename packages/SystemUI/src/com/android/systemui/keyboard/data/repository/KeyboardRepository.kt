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
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.keyboard.data.model.Keyboard
import com.android.systemui.keyboard.shared.model.BacklightModel
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.shareIn

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
    @Application private val applicationScope: CoroutineScope,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val inputManager: InputManager,
) : KeyboardRepository {

    private sealed interface DeviceChange
    private data class DeviceAdded(val deviceId: Int) : DeviceChange
    private object DeviceRemoved : DeviceChange
    private object FreshStart : DeviceChange

    /**
     * Emits collection of all currently connected keyboards and what was the last [DeviceChange].
     * It emits collection so that every new subscriber to this SharedFlow can get latest state of
     * all keyboards. Otherwise we might get into situation where subscriber timing on
     * initialization matter and later subscriber will only get latest device and will miss all
     * previous devices.
     */
    private val keyboardsChange: Flow<Pair<Collection<Int>, DeviceChange>> =
        conflatedCallbackFlow {
                var connectedDevices = inputManager.inputDeviceIds.toSet()
                val listener =
                    object : InputManager.InputDeviceListener {
                        override fun onInputDeviceAdded(deviceId: Int) {
                            connectedDevices = connectedDevices + deviceId
                            sendWithLogging(connectedDevices to DeviceAdded(deviceId))
                        }

                        override fun onInputDeviceChanged(deviceId: Int) = Unit

                        override fun onInputDeviceRemoved(deviceId: Int) {
                            connectedDevices = connectedDevices - deviceId
                            sendWithLogging(connectedDevices to DeviceRemoved)
                        }
                    }
                sendWithLogging(connectedDevices to FreshStart)
                inputManager.registerInputDeviceListener(listener, /* handler= */ null)
                awaitClose { inputManager.unregisterInputDeviceListener(listener) }
            }
            .map { (ids, change) -> ids.filter { id -> isPhysicalFullKeyboard(id) } to change }
            .shareIn(
                scope = applicationScope,
                started = SharingStarted.Lazily,
                replay = 1,
            )

    @FlowPreview
    override val newlyConnectedKeyboard: Flow<Keyboard> =
        keyboardsChange
            .flatMapConcat { (devices, operation) ->
                when (operation) {
                    FreshStart -> devices.asFlow()
                    is DeviceAdded -> flowOf(operation.deviceId)
                    is DeviceRemoved -> emptyFlow()
                }
            }
            .mapNotNull { deviceIdToKeyboard(it) }
            .flowOn(backgroundDispatcher)

    override val isAnyKeyboardConnected: Flow<Boolean> =
        keyboardsChange
            .map { (devices, _) -> devices.isNotEmpty() }
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
