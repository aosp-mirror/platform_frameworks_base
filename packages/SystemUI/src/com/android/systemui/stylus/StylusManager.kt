/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.stylus

import android.hardware.input.InputManager
import android.os.Handler
import android.util.ArrayMap
import android.view.InputDevice
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject

/**
 * A class which keeps track of InputDevice events related to stylus devices, and notifies
 * registered callbacks of stylus events.
 */
@SysUISingleton
class StylusManager
@Inject
constructor(
    private val inputManager: InputManager,
    @Background private val handler: Handler,
) : InputManager.InputDeviceListener {

    private val stylusCallbacks: CopyOnWriteArrayList<StylusCallback> = CopyOnWriteArrayList()
    // This map should only be accessed on the handler
    private val inputDeviceAddressMap: MutableMap<Int, String?> = ArrayMap()

    /**
     * Starts listening to InputManager InputDevice events. Will also load the InputManager snapshot
     * at time of starting.
     */
    fun startListener() {
        addExistingStylusToMap()
        inputManager.registerInputDeviceListener(this, handler)
    }

    /** Registers a StylusCallback to listen to stylus events. */
    fun registerCallback(callback: StylusCallback) {
        stylusCallbacks.add(callback)
    }

    /** Unregisters a StylusCallback. If StylusCallback is not registered, is a no-op. */
    fun unregisterCallback(callback: StylusCallback) {
        stylusCallbacks.remove(callback)
    }

    override fun onInputDeviceAdded(deviceId: Int) {
        val device: InputDevice = inputManager.getInputDevice(deviceId) ?: return
        if (!device.supportsSource(InputDevice.SOURCE_STYLUS)) return

        // TODO(b/257936830): get address once input api available
        val btAddress: String? = null
        inputDeviceAddressMap[deviceId] = btAddress
        executeStylusCallbacks { cb -> cb.onStylusAdded(deviceId) }

        if (btAddress != null) {
            executeStylusCallbacks { cb -> cb.onStylusBluetoothConnected(deviceId, btAddress) }
        }
    }

    override fun onInputDeviceChanged(deviceId: Int) {
        val device: InputDevice = inputManager.getInputDevice(deviceId) ?: return
        if (!device.supportsSource(InputDevice.SOURCE_STYLUS)) return

        // TODO(b/257936830): get address once input api available
        val currAddress: String? = null
        val prevAddress: String? = inputDeviceAddressMap[deviceId]
        inputDeviceAddressMap[deviceId] = currAddress

        if (prevAddress == null && currAddress != null) {
            executeStylusCallbacks { cb -> cb.onStylusBluetoothConnected(deviceId, currAddress) }
        }

        if (prevAddress != null && currAddress == null) {
            executeStylusCallbacks { cb -> cb.onStylusBluetoothDisconnected(deviceId, prevAddress) }
        }
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        if (!inputDeviceAddressMap.contains(deviceId)) return

        val btAddress: String? = inputDeviceAddressMap[deviceId]
        inputDeviceAddressMap.remove(deviceId)
        if (btAddress != null) {
            executeStylusCallbacks { cb -> cb.onStylusBluetoothDisconnected(deviceId, btAddress) }
        }
        executeStylusCallbacks { cb -> cb.onStylusRemoved(deviceId) }
    }

    private fun executeStylusCallbacks(run: (cb: StylusCallback) -> Unit) {
        stylusCallbacks.forEach(run)
    }

    private fun addExistingStylusToMap() {
        for (deviceId: Int in inputManager.inputDeviceIds) {
            val device: InputDevice = inputManager.getInputDevice(deviceId) ?: continue
            if (device.supportsSource(InputDevice.SOURCE_STYLUS)) {
                // TODO(b/257936830): get address once input api available
                inputDeviceAddressMap[deviceId] = null
            }
        }
    }

    /** Callback interface to receive events from the StylusManager. */
    interface StylusCallback {
        fun onStylusAdded(deviceId: Int) {}
        fun onStylusRemoved(deviceId: Int) {}
        fun onStylusBluetoothConnected(deviceId: Int, btAddress: String) {}
        fun onStylusBluetoothDisconnected(deviceId: Int, btAddress: String) {}
    }

    companion object {
        private val TAG = StylusManager::class.simpleName.orEmpty()
    }
}
