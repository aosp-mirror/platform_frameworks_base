/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.statusbar.policy.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import com.android.settingslib.bluetooth.CachedBluetoothDevice
import com.android.settingslib.bluetooth.LocalBluetoothManager
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Repository for information about bluetooth connections.
 *
 * Note: Right now, this class and [BluetoothController] co-exist. Any new code should go in this
 * implementation, but external clients should query [BluetoothController] instead of this class for
 * now.
 */
interface BluetoothRepository {
    /**
     * Fetches the connection statuses for the given [currentDevices] and invokes [callback] once
     * those statuses have been fetched. The fetching occurs on a background thread because IPCs may
     * be required to fetch the statuses (see b/271058380).
     */
    fun fetchConnectionStatusInBackground(
        currentDevices: Collection<CachedBluetoothDevice>,
        callback: ConnectionStatusFetchedCallback,
    )
}

/** Implementation of [BluetoothRepository]. */
@SysUISingleton
class BluetoothRepositoryImpl
@Inject
constructor(
    @Application private val scope: CoroutineScope,
    @Background private val bgDispatcher: CoroutineDispatcher,
    private val localBluetoothManager: LocalBluetoothManager?,
) : BluetoothRepository {
    override fun fetchConnectionStatusInBackground(
        currentDevices: Collection<CachedBluetoothDevice>,
        callback: ConnectionStatusFetchedCallback,
    ) {
        scope.launch {
            val result = fetchConnectionStatus(currentDevices)
            callback.onConnectionStatusFetched(result)
        }
    }

    private suspend fun fetchConnectionStatus(
        currentDevices: Collection<CachedBluetoothDevice>,
    ): ConnectionStatusModel {
        return withContext(bgDispatcher) {
            val minimumMaxConnectionState =
                localBluetoothManager?.bluetoothAdapter?.connectionState
                    ?: BluetoothProfile.STATE_DISCONNECTED
            var maxConnectionState =
                if (currentDevices.isEmpty()) {
                    minimumMaxConnectionState
                } else {
                    currentDevices
                        .maxOf { it.maxConnectionState }
                        .coerceAtLeast(minimumMaxConnectionState)
                }

            val connectedDevices = currentDevices.filter { it.isConnected }

            if (
                connectedDevices.isEmpty() && maxConnectionState == BluetoothAdapter.STATE_CONNECTED
            ) {
                // If somehow we think we are connected, but have no connected devices, we aren't
                // connected.
                maxConnectionState = BluetoothAdapter.STATE_DISCONNECTED
            }

            ConnectionStatusModel(maxConnectionState, connectedDevices)
        }
    }
}

data class ConnectionStatusModel(
    /** The maximum connection state out of all current devices. */
    val maxConnectionState: Int,
    /** A list of devices that are currently connected. */
    val connectedDevices: List<CachedBluetoothDevice>,
)

/** Callback notified when the new status has been fetched. */
fun interface ConnectionStatusFetchedCallback {
    fun onConnectionStatusFetched(status: ConnectionStatusModel)
}
