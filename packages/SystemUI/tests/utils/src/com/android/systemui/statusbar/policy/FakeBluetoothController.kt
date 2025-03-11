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
package com.android.systemui.statusbar.policy

import android.bluetooth.BluetoothAdapter
import com.android.internal.annotations.VisibleForTesting
import com.android.settingslib.bluetooth.CachedBluetoothDevice
import com.android.systemui.statusbar.policy.BluetoothController.Callback
import java.io.PrintWriter
import java.util.Collections
import java.util.concurrent.Executor

class FakeBluetoothController : BluetoothController {

    private var callbacks = mutableListOf<Callback>()
    private var enabled = false

    override fun addCallback(listener: Callback) {
        callbacks += listener
        listener.onBluetoothStateChange(isBluetoothEnabled)
    }

    override fun removeCallback(listener: Callback) {
        callbacks -= listener
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {}

    override fun isBluetoothSupported(): Boolean = false

    override fun isBluetoothEnabled(): Boolean = enabled

    override fun getBluetoothState(): Int = 0

    override fun isBluetoothConnected(): Boolean = false

    override fun isBluetoothConnecting(): Boolean = false

    override fun isBluetoothAudioProfileOnly(): Boolean = false

    override fun isBluetoothAudioActive(): Boolean = false

    override fun getConnectedDeviceName(): String? = null

    override fun setBluetoothEnabled(enabled: Boolean) {
        this.enabled = enabled
        callbacks.forEach { it.onBluetoothStateChange(enabled) }
    }

    override fun canConfigBluetooth(): Boolean = false

    override fun getConnectedDevices(): MutableList<CachedBluetoothDevice> = Collections.emptyList()

    override fun addOnMetadataChangedListener(
        device: CachedBluetoothDevice?,
        executor: Executor?,
        listener: BluetoothAdapter.OnMetadataChangedListener?,
    ) {}

    override fun removeOnMetadataChangedListener(
        device: CachedBluetoothDevice?,
        listener: BluetoothAdapter.OnMetadataChangedListener?,
    ) {}

    /** Trigger the [Callback.onBluetoothDevicesChanged] method for all registered callbacks. */
    @VisibleForTesting
    fun onBluetoothDevicesChanged() {
        callbacks.forEach { it.onBluetoothDevicesChanged() }
    }
}
