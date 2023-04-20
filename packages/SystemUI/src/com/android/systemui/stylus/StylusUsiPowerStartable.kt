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

import android.hardware.BatteryState
import android.hardware.input.InputManager
import android.util.Log
import android.view.InputDevice
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import java.util.concurrent.Executor
import javax.inject.Inject

/**
 * A [CoreStartable] that listens to USI stylus battery events, to manage the [StylusUsiPowerUI]
 * notification controller.
 */
@SysUISingleton
class StylusUsiPowerStartable
@Inject
constructor(
    private val stylusManager: StylusManager,
    private val inputManager: InputManager,
    private val stylusUsiPowerUi: StylusUsiPowerUI,
    private val featureFlags: FeatureFlags,
    @Background private val executor: Executor,
) : CoreStartable, StylusManager.StylusCallback, InputManager.InputDeviceBatteryListener {

    override fun onStylusAdded(deviceId: Int) {
        val device = inputManager.getInputDevice(deviceId) ?: return

        if (!device.isExternal) {
            registerBatteryListener(deviceId)
        }
    }

    override fun onStylusBluetoothConnected(deviceId: Int, btAddress: String) {
        stylusUsiPowerUi.refresh()
    }

    override fun onStylusBluetoothDisconnected(deviceId: Int, btAddress: String) {
        stylusUsiPowerUi.refresh()
    }

    override fun onStylusRemoved(deviceId: Int) {
        val device = inputManager.getInputDevice(deviceId) ?: return

        if (!device.isExternal) {
            unregisterBatteryListener(deviceId)
        }
    }

    override fun onBatteryStateChanged(
        deviceId: Int,
        eventTimeMillis: Long,
        batteryState: BatteryState
    ) {
        if (batteryState.isPresent) {
            stylusUsiPowerUi.updateBatteryState(batteryState)
        }
    }

    private fun registerBatteryListener(deviceId: Int) {
        try {
            inputManager.addInputDeviceBatteryListener(deviceId, executor, this)
        } catch (e: SecurityException) {
            Log.e(TAG, "$e: Failed to register battery listener for $deviceId.")
        }
    }

    private fun unregisterBatteryListener(deviceId: Int) {
        try {
            inputManager.removeInputDeviceBatteryListener(deviceId, this)
        } catch (e: SecurityException) {
            Log.e(TAG, "$e: Failed to unregister battery listener for $deviceId.")
        }
    }

    override fun start() {
        if (!featureFlags.isEnabled(Flags.ENABLE_USI_BATTERY_NOTIFICATIONS)) return
        addBatteryListenerForInternalStyluses()

        stylusManager.registerCallback(this)
        stylusManager.startListener()
    }

    private fun addBatteryListenerForInternalStyluses() {
        // For most devices, an active stylus is represented by an internal InputDevice.
        // This InputDevice will be present in InputManager before CoreStartables run,
        // and will not be removed. In many cases, it reports the battery level of the stylus.
        inputManager.inputDeviceIds
            .asSequence()
            .mapNotNull { inputManager.getInputDevice(it) }
            .filter { it.supportsSource(InputDevice.SOURCE_STYLUS) }
            .forEach { onStylusAdded(it.id) }
    }

    companion object {
        private val TAG = StylusUsiPowerStartable::class.simpleName.orEmpty()
    }
}
