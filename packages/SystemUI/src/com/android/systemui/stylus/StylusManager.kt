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
import android.hardware.input.InputSettings
import android.os.Handler
import android.util.ArrayMap
import android.util.Log
import android.view.InputDevice
import com.android.internal.annotations.VisibleForTesting
import com.android.internal.logging.InstanceId
import com.android.internal.logging.InstanceIdSequence
import com.android.internal.logging.UiEventLogger
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.log.DebugLogger.debugLog
import com.android.systemui.shared.hardware.hasInputDevice
import com.android.systemui.shared.hardware.isInternalStylusSource
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
    private val uiEventLogger: UiEventLogger,
) :
    InputManager.InputDeviceListener,
    InputManager.InputDeviceBatteryListener,
    BluetoothAdapter.OnMetadataChangedListener {

    private val stylusCallbacks: CopyOnWriteArrayList<StylusCallback> = CopyOnWriteArrayList()

    // This map should only be accessed on the handler
    private val inputDeviceAddressMap: MutableMap<Int, String?> = ArrayMap()
    private val inputDeviceBtSessionIdMap: MutableMap<Int, InstanceId> = ArrayMap()

    // These variables should only be accessed on the handler
    private var hasStarted: Boolean = false
    private var isInUsiSession: Boolean = false
    private var usiSessionId: InstanceId? = null

    @VisibleForTesting var instanceIdSequence = InstanceIdSequence(1 shl 13)

    /**
     * Starts listening to InputManager InputDevice events. Will also load the InputManager snapshot
     * at time of starting.
     */
    fun startListener() {
        handler.post {
            if (hasStarted) return@post
            debugLog { "Listener has started." }

            hasStarted = true
            isInUsiSession =
                inputManager.hasInputDevice {
                    it.isInternalStylusSource && isBatteryStateValid(it.batteryState)
                }
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

    override fun onInputDeviceAdded(deviceId: Int) {
        if (!hasStarted) return

        val device: InputDevice = inputManager.getInputDevice(deviceId) ?: return
        if (!device.supportsSource(InputDevice.SOURCE_STYLUS)) return
        debugLog {
            "Stylus InputDevice added: $deviceId ${device.name}, " +
                "External: ${device.isExternal}"
        }

        if (!device.isExternal) {
            registerBatteryListener(deviceId)
        }

        val btAddress: String? = device.bluetoothAddress
        inputDeviceAddressMap[deviceId] = btAddress
        executeStylusCallbacks { cb -> cb.onStylusAdded(deviceId) }

        if (btAddress != null) {
            onStylusUsed()
            onStylusBluetoothConnected(deviceId, btAddress)
            executeStylusCallbacks { cb -> cb.onStylusBluetoothConnected(deviceId, btAddress) }
        }
    }

    override fun onInputDeviceChanged(deviceId: Int) {
        if (!hasStarted) return

        val device: InputDevice = inputManager.getInputDevice(deviceId) ?: return
        if (!device.supportsSource(InputDevice.SOURCE_STYLUS)) return
        debugLog { "Stylus InputDevice changed: $deviceId ${device.name}" }

        val currAddress: String? = device.bluetoothAddress
        val prevAddress: String? = inputDeviceAddressMap[deviceId]
        inputDeviceAddressMap[deviceId] = currAddress

        if (prevAddress == null && currAddress != null) {
            onStylusBluetoothConnected(deviceId, currAddress)
            executeStylusCallbacks { cb -> cb.onStylusBluetoothConnected(deviceId, currAddress) }
        }

        if (prevAddress != null && currAddress == null) {
            onStylusBluetoothDisconnected(deviceId, prevAddress)
            executeStylusCallbacks { cb -> cb.onStylusBluetoothDisconnected(deviceId, prevAddress) }
        }
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        if (!hasStarted) return

        if (!inputDeviceAddressMap.contains(deviceId)) return
        debugLog { "Stylus InputDevice removed: $deviceId" }

        unregisterBatteryListener(deviceId)

        val btAddress: String? = inputDeviceAddressMap[deviceId]
        inputDeviceAddressMap.remove(deviceId)
        if (btAddress != null) {
            onStylusBluetoothDisconnected(deviceId, btAddress)
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

            debugLog {
                "Charging state metadata changed for device $inputDeviceId " +
                    "${device.address}: $isCharging"
            }

            executeStylusCallbacks { cb ->
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

            debugLog {
                "Battery state changed for $deviceId. " +
                    "batteryState present: ${batteryState.isPresent}, " +
                    "capacity: ${batteryState.capacity}"
            }

            val batteryStateValid = isBatteryStateValid(batteryState)
            trackAndLogUsiSession(deviceId, batteryStateValid)
            if (batteryStateValid) {
                onStylusUsed()
            }

            executeStylusCallbacks { cb ->
                cb.onStylusUsiBatteryStateChanged(deviceId, eventTimeMillis, batteryState)
            }
        }
    }

    private fun onStylusBluetoothConnected(deviceId: Int, btAddress: String) {
        trackAndLogBluetoothSession(deviceId, btAddress, true)
        val device: BluetoothDevice = bluetoothAdapter?.getRemoteDevice(btAddress) ?: return
        try {
            bluetoothAdapter.addOnMetadataChangedListener(device, executor, this)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "$e: Metadata listener already registered for device. Ignoring.")
        }
    }

    private fun onStylusBluetoothDisconnected(deviceId: Int, btAddress: String) {
        trackAndLogBluetoothSession(deviceId, btAddress, false)
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
        if (!featureFlags.isEnabled(Flags.TRACK_STYLUS_EVER_USED)) return
        if (InputSettings.isStylusEverUsed(context)) return

        debugLog { "Stylus used for the first time." }
        InputSettings.setStylusEverUsed(context, true)
        executeStylusCallbacks { cb -> cb.onStylusFirstUsed() }
    }

    /**
     * Uses the input device battery state to track whether a current USI session is active. The
     * InputDevice battery state updates USI battery on USI stylus input, and removes the last-known
     * USI stylus battery presence after 1 hour of not detecting input. As SysUI and StylusManager
     * is persistently running, relies on tracking sessions via an in-memory isInUsiSession boolean.
     */
    private fun trackAndLogUsiSession(deviceId: Int, batteryStateValid: Boolean) {
        // TODO(b/268618918) handle cases where an invalid battery callback from a previous stylus
        //  is sent after the actual valid callback
        val hasBtConnection = if (inputDeviceBtSessionIdMap.isEmpty()) 0 else 1

        if (batteryStateValid && usiSessionId == null) {
            debugLog { "USI battery newly present, entering new USI session: $deviceId" }
            usiSessionId = instanceIdSequence.newInstanceId()
            uiEventLogger.logWithInstanceIdAndPosition(
                StylusUiEvent.USI_STYLUS_BATTERY_PRESENCE_FIRST_DETECTED,
                0,
                null,
                usiSessionId,
                hasBtConnection,
            )
        } else if (!batteryStateValid && usiSessionId != null) {
            debugLog { "USI battery newly absent, exiting USI session: $deviceId" }
            uiEventLogger.logWithInstanceIdAndPosition(
                StylusUiEvent.USI_STYLUS_BATTERY_PRESENCE_REMOVED,
                0,
                null,
                usiSessionId,
                hasBtConnection,
            )
            usiSessionId = null
        }
    }

    private fun trackAndLogBluetoothSession(
        deviceId: Int,
        btAddress: String,
        btConnected: Boolean
    ) {
        debugLog {
            "Bluetooth stylus ${if (btConnected) "connected" else "disconnected"}:" +
                " $deviceId $btAddress"
        }

        if (btConnected) {
            inputDeviceBtSessionIdMap[deviceId] = instanceIdSequence.newInstanceId()
            uiEventLogger.logWithInstanceId(
                StylusUiEvent.BLUETOOTH_STYLUS_CONNECTED,
                0,
                null,
                inputDeviceBtSessionIdMap[deviceId]
            )
        } else {
            uiEventLogger.logWithInstanceId(
                StylusUiEvent.BLUETOOTH_STYLUS_DISCONNECTED,
                0,
                null,
                inputDeviceBtSessionIdMap[deviceId]
            )
            inputDeviceBtSessionIdMap.remove(deviceId)
        }
    }

    private fun isBatteryStateValid(batteryState: BatteryState): Boolean {
        return batteryState.isPresent && batteryState.capacity > 0.0f
    }

    private fun executeStylusCallbacks(run: (cb: StylusCallback) -> Unit) {
        stylusCallbacks.forEach(run)
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
                inputDeviceAddressMap[deviceId] = device.bluetoothAddress

                if (!device.isExternal) { // TODO(b/263556967): add supportsUsi check once available
                    // For most devices, an active (non-bluetooth) stylus is represented by an
                    // internal InputDevice. This InputDevice will be present in InputManager
                    // before CoreStartables run, and will not be removed.
                    // In many cases, it reports the battery level of the stylus.
                    registerBatteryListener(deviceId)
                } else {
                    device.bluetoothAddress?.let { onStylusBluetoothConnected(deviceId, it) }
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
        val TAG = StylusManager::class.simpleName.orEmpty()
    }
}
