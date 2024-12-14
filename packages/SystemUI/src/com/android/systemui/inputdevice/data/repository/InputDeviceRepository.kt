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
 *
 */

package com.android.systemui.inputdevice.data.repository

import android.annotation.SuppressLint
import android.hardware.input.InputManager
import android.os.Handler
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.shareIn

@SysUISingleton
class InputDeviceRepository
@Inject
constructor(
    @Background private val backgroundHandler: Handler,
    @Background private val backgroundScope: CoroutineScope,
    private val inputManager: InputManager
) {

    sealed interface DeviceChange

    data class DeviceAdded(val deviceId: Int) : DeviceChange

    data class DeviceRemoved(val deviceId: Int) : DeviceChange

    data object FreshStart : DeviceChange

    /**
     * Emits collection of all currently connected keyboards and what was the last [DeviceChange].
     * It emits collection so that every new subscriber to this SharedFlow can get latest state of
     * all keyboards. Otherwise we might get into situation where subscriber timing on
     * initialization matter and later subscriber will only get latest device and will miss all
     * previous devices.
     */
    // TODO(b/351984587): Replace with StateFlow
    @SuppressLint("SharedFlowCreation")
    val deviceChange: Flow<Pair<Collection<Int>, DeviceChange>> =
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
                            sendWithLogging(connectedDevices to DeviceRemoved(deviceId))
                        }
                    }
                sendWithLogging(connectedDevices to FreshStart)
                inputManager.registerInputDeviceListener(listener, backgroundHandler)
                awaitClose { inputManager.unregisterInputDeviceListener(listener) }
            }
            .shareIn(
                scope = backgroundScope,
                started = SharingStarted.Lazily,
                replay = 1,
            )

    private fun <T> SendChannel<T>.sendWithLogging(element: T) {
        trySendWithFailureLogging(element, TAG)
    }

    companion object {
        const val TAG = "InputDeviceRepository"
    }
}
