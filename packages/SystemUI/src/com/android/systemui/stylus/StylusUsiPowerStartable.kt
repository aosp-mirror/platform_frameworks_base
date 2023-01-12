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
import android.view.InputDevice
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
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
) : CoreStartable, StylusManager.StylusCallback, StylusManager.StylusBatteryCallback {

    override fun onStylusBluetoothConnected(deviceId: Int, btAddress: String) {
        stylusUsiPowerUi.refresh()
    }

    override fun onStylusBluetoothDisconnected(deviceId: Int, btAddress: String) {
        stylusUsiPowerUi.refresh()
    }

    override fun onStylusUsiBatteryStateChanged(
        deviceId: Int,
        eventTimeMillis: Long,
        batteryState: BatteryState
    ) {
        if (batteryState.isPresent && batteryState.capacity > 0f) {
            stylusUsiPowerUi.updateBatteryState(deviceId, batteryState)
        }
    }

    override fun start() {
        if (!featureFlags.isEnabled(Flags.ENABLE_USI_BATTERY_NOTIFICATIONS)) return
        if (!hostDeviceSupportsStylusInput()) return

        stylusUsiPowerUi.init()
        stylusManager.registerCallback(this)
        stylusManager.startListener()
    }

    private fun hostDeviceSupportsStylusInput(): Boolean {
        return inputManager.inputDeviceIds
            .asSequence()
            .mapNotNull { inputManager.getInputDevice(it) }
            .any { it.supportsSource(InputDevice.SOURCE_STYLUS) && !it.isExternal }
    }

    companion object {
        private val TAG = StylusUsiPowerStartable::class.simpleName.orEmpty()
    }
}
