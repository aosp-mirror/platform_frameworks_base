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

import android.content.Context
import android.hardware.BatteryState
import android.hardware.input.InputManager
import android.os.Handler
import android.util.Log
import android.view.InputDevice
import androidx.annotation.VisibleForTesting
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import java.util.concurrent.Executor
import javax.inject.Inject

/**
 * A listener that detects when a stylus has first been used, by detecting 1) the presence of an
 * internal SOURCE_STYLUS with a battery, or 2) any added SOURCE_STYLUS device with a bluetooth
 * address.
 */
@SysUISingleton
class StylusFirstUsageListener
@Inject
constructor(
    private val context: Context,
    private val inputManager: InputManager,
    private val stylusManager: StylusManager,
    private val featureFlags: FeatureFlags,
    @Background private val executor: Executor,
    @Background private val handler: Handler,
) : CoreStartable, StylusManager.StylusCallback, InputManager.InputDeviceBatteryListener {

    // Set must be only accessed from the background handler, which is the same handler that
    // runs the StylusManager callbacks.
    private val internalStylusDeviceIds: MutableSet<Int> = mutableSetOf()
    @VisibleForTesting var hasStarted = false

    override fun start() {
        if (true) return // TODO(b/261826950): remove on main
        if (hasStarted) return
        if (!featureFlags.isEnabled(Flags.TRACK_STYLUS_EVER_USED)) return
        if (inputManager.isStylusEverUsed(context)) return
        if (!hostDeviceSupportsStylusInput()) return

        hasStarted = true
        inputManager.inputDeviceIds.forEach(this::onStylusAdded)
        stylusManager.registerCallback(this)
        stylusManager.startListener()
    }

    override fun onStylusAdded(deviceId: Int) {
        if (!hasStarted) return

        val device = inputManager.getInputDevice(deviceId) ?: return
        if (device.isExternal || !device.supportsSource(InputDevice.SOURCE_STYLUS)) return

        try {
            inputManager.addInputDeviceBatteryListener(deviceId, executor, this)
            internalStylusDeviceIds += deviceId
        } catch (e: SecurityException) {
            Log.e(TAG, "$e: Failed to register battery listener for $deviceId ${device.name}.")
        }
    }

    override fun onStylusRemoved(deviceId: Int) {
        if (!hasStarted) return

        if (!internalStylusDeviceIds.contains(deviceId)) return
        try {
            inputManager.removeInputDeviceBatteryListener(deviceId, this)
            internalStylusDeviceIds.remove(deviceId)
        } catch (e: SecurityException) {
            Log.e(TAG, "$e: Failed to remove registered battery listener for $deviceId.")
        }
    }

    override fun onStylusBluetoothConnected(deviceId: Int, btAddress: String) {
        if (!hasStarted) return

        onRemoteDeviceFound()
    }

    override fun onBatteryStateChanged(
        deviceId: Int,
        eventTimeMillis: Long,
        batteryState: BatteryState
    ) {
        if (!hasStarted) return

        if (batteryState.isPresent) {
            onRemoteDeviceFound()
        }
    }

    private fun onRemoteDeviceFound() {
        inputManager.setStylusEverUsed(context, true)
        cleanupListeners()
    }

    private fun cleanupListeners() {
        stylusManager.unregisterCallback(this)
        handler.post {
            internalStylusDeviceIds.forEach {
                inputManager.removeInputDeviceBatteryListener(it, this)
            }
        }
    }

    private fun hostDeviceSupportsStylusInput(): Boolean {
        return inputManager.inputDeviceIds
            .asSequence()
            .mapNotNull { inputManager.getInputDevice(it) }
            .any { it.supportsSource(InputDevice.SOURCE_STYLUS) && !it.isExternal }
    }

    companion object {
        private val TAG = StylusFirstUsageListener::class.simpleName.orEmpty()
    }
}
