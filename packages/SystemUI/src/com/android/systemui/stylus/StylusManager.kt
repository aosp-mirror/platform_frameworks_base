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

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.hardware.BatteryState
import android.hardware.input.InputManager
import android.os.Handler
import android.util.ArrayMap
import android.util.Log
import android.view.InputDevice
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import javax.inject.Inject

/**
 * A class which keeps track of InputDevice events related to stylus devices, and notifies
 * registered callbacks of stylus events.
 */
@SysUISingleton
class StylusManager
@Inject
constructor(
    private val context: Context,
    private val inputManager: InputManager,
    private val bluetoothAdapter: BluetoothAdapter?,
    @Background private val handler: Handler,
    @Background private val executor: Executor,
    private val featureFlags: FeatureFlags,
) :
    InputManager.InputDeviceListener,
    InputManager.InputDeviceBatteryListener,
    BluetoothAdapter.OnMetadataChangedListener {

    private val stylusCallbacks: CopyOnWriteArrayList<StylusCallback> = CopyOnWriteArrayList()
    private val stylusBatteryCallbacks: CopyOnWriteArrayList<StylusBatteryCallback> =
        CopyOnWriteArrayList()
    // This map should only be accessed on the handler
    private val inputDeviceAddressMap: MutableMap<Int, String?> = ArrayMap()
    // This variable should only be accessed on the handler
    private var hasStarted: Boolean = false

    /**
     * Starts listening to InputManager InputDevice events. Will also load the InputManager snapshot
     * at time of starting.
     */
    fun startListener() {
        handler.post {
            if (hasStarted) return@post
            hasStarted = true
            addExistingStylusToMap()

            inputManager.registerInputDeviceListener(this, handler)
        }
    }

    /** Registers a StylusCallback to listen to stylus events. */
    fun registerCallback(callback: StylusCallback) {
        stylusCallbacks.add(callback)
    }

    /** Unregisters a StylusCallback. If StylusCallback is not registered, is a no-op. */
    fun unregisterCallback(callback: StylusCallback) {
        stylusCallbacks.remove(callback)
    }

    fun registerBatteryCallback(callback: StylusBatteryCallback) {
        stylusBatteryCallbacks.add(callback)
    }

    fun unregisterBatteryCallback(callback: StylusBatteryCallback) {
        stylusBatteryCallbacks.remove(callback)
    }

    override fun onInputDeviceAdded(deviceId: Int) {
        if (!hasStarted) return

        val device: InputDevice = inputManager.getInputDevice(deviceId) ?: return
        if (!device.supportsSource(InputDevice.SOURCE_STYLUS)) return

        if (!device.isExternal) {
            registerBatteryListener(deviceId)
        }

        // TODO(b/257936830): get address once input api available
        val btAddress: String? = null
        inputDeviceAddressMap[deviceId] = btAddress
        executeStylusCallbacks { cb -> cb.onStylusAdded(deviceId) }

        if (btAddress != null) {
            onStylusUsed()
            onStylusBluetoothConnected(btAddress)
            executeStylusCallbacks { cb -> cb.onStylusBluetoothConnected(deviceId, btAddress) }
        }
    }

    override fun onInputDeviceChanged(deviceId: Int) {
        if (!hasStarted) return

        val device: InputDevice = inputManager.getInputDevice(deviceId) ?: return
        if (!device.supportsSource(InputDevice.SOURCE_STYLUS)) return

        // TODO(b/257936830): get address once input api available
        val currAddress: String? = null
        val prevAddress: String? = inputDeviceAddressMap[deviceId]
        inputDeviceAddressMap[deviceId] = currAddress

        if (prevAddress == null && currAddress != null) {
            onStylusBluetoothConnected(currAddress)
            executeStylusCallbacks { cb -> cb.onStylusBluetoothConnected(deviceId, currAddress) }
        }

        if (prevAddress != null && currAddress == null) {
            onStylusBluetoothDisconnected(prevAddress)
            executeStylusCallbacks { cb -> cb.onStylusBluetoothDisconnected(deviceId, prevAddress) }
        }
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        if (!hasStarted) return

        if (!inputDeviceAddressMap.contains(deviceId)) return
        unregisterBatteryListener(deviceId)

        val btAddress: String? = inputDeviceAddressMap[deviceId]
        inputDeviceAddressMap.remove(deviceId)
        if (btAddress != null) {
            onStylusBluetoothDisconnected(btAddress)
            executeStylusCallbacks { cb -> cb.onStylusBluetoothDisconnected(deviceId, btAddress) }
        }
        executeStylusCallbacks { cb -> cb.onStylusRemoved(deviceId) }
    }

    override fun onMetadataChanged(device: BluetoothDevice, key: Int, value: ByteArray?) {
        handler.post {
            if (!hasStarted) return@post

            if (key != BluetoothDevice.METADATA_MAIN_CHARGING || value == null) return@post

            val inputDeviceId: Int =
                inputDeviceAddressMap.filterValues { it == device.address }.keys.firstOrNull()
                    ?: return@post

            val isCharging = String(value) == "true"

            executeStylusBatteryCallbacks { cb ->
                cb.onStylusBluetoothChargingStateChanged(inputDeviceId, device, isCharging)
            }
        }
    }

    override fun onBatteryStateChanged(
        deviceId: Int,
        eventTimeMillis: Long,
        batteryState: BatteryState
    ) {
        handler.post {
            if (!hasStarted) return@post

            if (batteryState.isPresent) {
                onStylusUsed()
            }

            executeStylusBatteryCallbacks { cb ->
                cb.onStylusUsiBatteryStateChanged(deviceId, eventTimeMillis, batteryState)
            }
        }
    }

    private fun onStylusBluetoothConnected(btAddress: String) {
        val device: BluetoothDevice = bluetoothAdapter?.getRemoteDevice(btAddress) ?: return
        try {
            bluetoothAdapter.addOnMetadataChangedListener(device, executor, this)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "$e: Metadata listener already registered for device. Ignoring.")
        }
    }

    private fun onStylusBluetoothDisconnected(btAddress: String) {
        val device: BluetoothDevice = bluetoothAdapter?.getRemoteDevice(btAddress) ?: return
        try {
            bluetoothAdapter.removeOnMetadataChangedListener(device, this)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "$e: Metadata listener does not exist for device. Ignoring.")
        }
    }

    /**
     * An InputDevice that supports [InputDevice.SOURCE_STYLUS] may still be present even when a
     * physical stylus device has never been used. This method is run when 1) a USI stylus battery
     * event happens, or 2) a bluetooth stylus is connected, as they are both indicators that a
     * physical stylus device has actually been used.
     */
    private fun onStylusUsed() {
        if (true) return // TODO(b/261826950): remove on main
        if (!featureFlags.isEnabled(Flags.TRACK_STYLUS_EVER_USED)) return
        if (inputManager.isStylusEverUsed(context)) return

        inputManager.setStylusEverUsed(context, true)
        executeStylusCallbacks { cb -> cb.onStylusFirstUsed() }
    }

    private fun executeStylusCallbacks(run: (cb: StylusCallback) -> Unit) {
        stylusCallbacks.forEach(run)
    }

    private fun executeStylusBatteryCallbacks(run: (cb: StylusBatteryCallback) -> Unit) {
        stylusBatteryCallbacks.forEach(run)
    }

    private fun registerBatteryListener(deviceId: Int) {
        try {
            inputManager.addInputDeviceBatteryListener(deviceId, executor, this)
        } catch (e: SecurityException) {
            Log.e(TAG, "$e: Failed to register battery listener for $deviceId.")
        }
    }

    private fun unregisterBatteryListener(deviceId: Int) {
        // If deviceId wasn't registered, the result is a no-op, so an "is registered"
        // check is not needed.
        try {
            inputManager.removeInputDeviceBatteryListener(deviceId, this)
        } catch (e: SecurityException) {
            Log.e(TAG, "$e: Failed to remove registered battery listener for $deviceId.")
        }
    }

    private fun addExistingStylusToMap() {
        for (deviceId: Int in inputManager.inputDeviceIds) {
            val device: InputDevice = inputManager.getInputDevice(deviceId) ?: continue
            if (device.supportsSource(InputDevice.SOURCE_STYLUS)) {
                // TODO(b/257936830): get address once input api available
                inputDeviceAddressMap[deviceId] = null

                if (!device.isExternal) { // TODO(b/263556967): add supportsUsi check once available
                    // For most devices, an active (non-bluetooth) stylus is represented by an
                    // internal InputDevice. This InputDevice will be present in InputManager
                    // before CoreStartables run, and will not be removed.
                    // In many cases, it reports the battery level of the stylus.
                    registerBatteryListener(deviceId)
                }
            }
        }
    }

    /**
     * Callback interface to receive events from the StylusManager. All callbacks are run on the
     * same background handler.
     */
    interface StylusCallback {
        fun onStylusAdded(deviceId: Int) {}
        fun onStylusRemoved(deviceId: Int) {}
        fun onStylusBluetoothConnected(deviceId: Int, btAddress: String) {}
        fun onStylusBluetoothDisconnected(deviceId: Int, btAddress: String) {}
        fun onStylusFirstUsed() {}
    }

    /**
     * Callback interface to receive stylus battery events from the StylusManager. All callbacks are
     * runs on the same background handler.
     */
    interface StylusBatteryCallback {
        fun onStylusBluetoothChargingStateChanged(
            inputDeviceId: Int,
            btDevice: BluetoothDevice,
            isCharging: Boolean
        ) {}
        fun onStylusUsiBatteryStateChanged(
            deviceId: Int,
            eventTimeMillis: Long,
            batteryState: BatteryState,
        ) {}
    }

    companion object {
        private val TAG = StylusManager::class.simpleName.orEmpty()
    }
}
