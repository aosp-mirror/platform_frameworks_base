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

package android.companion.multidevices.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.util.Log
import androidx.test.uiautomator.UiDevice
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Controls the local Bluetooth adapter for testing. */
class BluetoothController(
    private val context: Context,
    private val adapter: BluetoothAdapter,
    private val ui: UiDevice
) {
    companion object {
        private const val TAG = "CDM_BluetoothController"
    }

    private val bluetoothUi by lazy { BluetoothUi(ui) }

    init {
        Log.d(TAG, "Registering pairing listener.")
        context.registerReceiver(
            PairingBroadcastReceiver(),
            IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST)
        )
    }

    val isEnabled: Boolean
        get() = adapter.isEnabled

    /** Turns on the local Bluetooth adapter */
    fun enableBluetooth() {
        if (isEnabled) return

        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
        bluetoothUi.clickAllowButton()
        waitFor { adapter.state == BluetoothAdapter.STATE_ON }
    }

    /** Become discoverable for specified duration */
    fun becomeDiscoverable(duration: Duration = 15.seconds) {
        enableBluetooth()

        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        intent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, duration.inWholeSeconds)
        context.startActivity(intent)
        bluetoothUi.clickAllowButton()
    }

    /** Unpair all devices for cleanup */
    fun unpairAllDevices() {
        for (device in adapter.bondedDevices) {
            Log.d(TAG, "Unpairing $device.")
            if (!device.removeBond()) continue
            waitFor { device.bondState == BluetoothDevice.BOND_NONE }
        }
    }

    private fun waitFor(
        interval: Duration = 1.seconds,
        timeout: Duration = 5.seconds,
        condition: () -> Boolean
    ) {
        var elapsed = 0L
        while (elapsed < timeout.inWholeMilliseconds) {
            if (condition.invoke()) return
            SystemClock.sleep(interval.inWholeMilliseconds)
            elapsed += interval.inWholeMilliseconds
        }
        throw TimeoutException("Bluetooth did not become an expected state.")
    }

    inner class PairingBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "Received broadcast for ${intent.action}")

            // onReceive() somehow blocks pairing prompt from launching
            Thread { bluetoothUi.confirmPairingRequest() }.start()
            context.unregisterReceiver(this)
        }
    }
}
