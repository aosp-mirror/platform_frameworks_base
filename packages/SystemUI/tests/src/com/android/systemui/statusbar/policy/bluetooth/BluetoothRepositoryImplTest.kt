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

import android.bluetooth.BluetoothProfile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.settingslib.bluetooth.CachedBluetoothDevice
import com.android.settingslib.bluetooth.LocalBluetoothAdapter
import com.android.settingslib.bluetooth.LocalBluetoothManager
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class BluetoothRepositoryImplTest : SysuiTestCase() {

    private lateinit var underTest: BluetoothRepositoryImpl

    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var dispatcher: TestDispatcher
    private lateinit var testScope: TestScope

    @Mock private lateinit var localBluetoothManager: LocalBluetoothManager
    @Mock private lateinit var bluetoothAdapter: LocalBluetoothAdapter

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(localBluetoothManager.bluetoothAdapter).thenReturn(bluetoothAdapter)

        scheduler = TestCoroutineScheduler()
        dispatcher = StandardTestDispatcher(scheduler)
        testScope = TestScope(dispatcher)

        underTest =
            BluetoothRepositoryImpl(testScope.backgroundScope, dispatcher, localBluetoothManager)
    }

    @Test
    fun fetchConnectionStatusInBackground_currentDevicesEmpty_maxStateIsManagerState() {
        whenever(bluetoothAdapter.connectionState).thenReturn(BluetoothProfile.STATE_CONNECTING)

        val status = fetchConnectionStatus(currentDevices = emptyList())

        assertThat(status.maxConnectionState).isEqualTo(BluetoothProfile.STATE_CONNECTING)
    }

    @Test
    fun fetchConnectionStatusInBackground_currentDevicesEmpty_nullManager_maxStateIsDisconnected() {
        // This CONNECTING state should be unused because localBluetoothManager is null
        whenever(bluetoothAdapter.connectionState).thenReturn(BluetoothProfile.STATE_CONNECTING)
        underTest =
            BluetoothRepositoryImpl(
                testScope.backgroundScope,
                dispatcher,
                localBluetoothManager = null,
            )

        val status = fetchConnectionStatus(currentDevices = emptyList())

        assertThat(status.maxConnectionState).isEqualTo(BluetoothProfile.STATE_DISCONNECTED)
    }

    @Test
    fun fetchConnectionStatusInBackground_managerStateLargerThanDeviceStates_maxStateIsManager() {
        whenever(bluetoothAdapter.connectionState).thenReturn(BluetoothProfile.STATE_CONNECTING)
        val device1 =
            mock<CachedBluetoothDevice>().also {
                whenever(it.maxConnectionState).thenReturn(BluetoothProfile.STATE_DISCONNECTED)
            }
        val device2 =
            mock<CachedBluetoothDevice>().also {
                whenever(it.maxConnectionState).thenReturn(BluetoothProfile.STATE_DISCONNECTED)
            }

        val status = fetchConnectionStatus(currentDevices = listOf(device1, device2))

        assertThat(status.maxConnectionState).isEqualTo(BluetoothProfile.STATE_CONNECTING)
    }

    @Test
    fun fetchConnectionStatusInBackground_oneCurrentDevice_maxStateIsDeviceState() {
        whenever(bluetoothAdapter.connectionState).thenReturn(BluetoothProfile.STATE_DISCONNECTED)
        val device =
            mock<CachedBluetoothDevice>().also {
                whenever(it.maxConnectionState).thenReturn(BluetoothProfile.STATE_CONNECTING)
            }

        val status = fetchConnectionStatus(currentDevices = listOf(device))

        assertThat(status.maxConnectionState).isEqualTo(BluetoothProfile.STATE_CONNECTING)
    }

    @Test
    fun fetchConnectionStatusInBackground_multipleDevices_maxStateIsHighestState() {
        whenever(bluetoothAdapter.connectionState).thenReturn(BluetoothProfile.STATE_DISCONNECTED)

        val device1 =
            mock<CachedBluetoothDevice>().also {
                whenever(it.maxConnectionState).thenReturn(BluetoothProfile.STATE_CONNECTING)
                whenever(it.isConnected).thenReturn(false)
            }
        val device2 =
            mock<CachedBluetoothDevice>().also {
                whenever(it.maxConnectionState).thenReturn(BluetoothProfile.STATE_CONNECTED)
                whenever(it.isConnected).thenReturn(true)
            }

        val status = fetchConnectionStatus(currentDevices = listOf(device1, device2))

        assertThat(status.maxConnectionState).isEqualTo(BluetoothProfile.STATE_CONNECTED)
    }

    @Test
    fun fetchConnectionStatusInBackground_devicesNotConnected_maxStateIsDisconnected() {
        whenever(bluetoothAdapter.connectionState).thenReturn(BluetoothProfile.STATE_CONNECTING)

        // WHEN the devices say their state is CONNECTED but [isConnected] is false
        val device1 =
            mock<CachedBluetoothDevice>().also {
                whenever(it.maxConnectionState).thenReturn(BluetoothProfile.STATE_CONNECTED)
                whenever(it.isConnected).thenReturn(false)
            }
        val device2 =
            mock<CachedBluetoothDevice>().also {
                whenever(it.maxConnectionState).thenReturn(BluetoothProfile.STATE_CONNECTED)
                whenever(it.isConnected).thenReturn(false)
            }

        val status = fetchConnectionStatus(currentDevices = listOf(device1, device2))

        // THEN the max state is DISCONNECTED
        assertThat(status.maxConnectionState).isEqualTo(BluetoothProfile.STATE_DISCONNECTED)
    }

    @Test
    fun fetchConnectionStatusInBackground_currentDevicesEmpty_connectedDevicesEmpty() {
        val status = fetchConnectionStatus(currentDevices = emptyList())

        assertThat(status.connectedDevices).isEmpty()
    }

    @Test
    fun fetchConnectionStatusInBackground_oneCurrentDeviceDisconnected_connectedDevicesEmpty() {
        val device =
            mock<CachedBluetoothDevice>().also { whenever(it.isConnected).thenReturn(false) }

        val status = fetchConnectionStatus(currentDevices = listOf(device))

        assertThat(status.connectedDevices).isEmpty()
    }

    @Test
    fun fetchConnectionStatusInBackground_oneCurrentDeviceConnected_connectedDevicesHasDevice() {
        val device =
            mock<CachedBluetoothDevice>().also { whenever(it.isConnected).thenReturn(true) }

        val status = fetchConnectionStatus(currentDevices = listOf(device))

        assertThat(status.connectedDevices).isEqualTo(listOf(device))
    }

    @Test
    fun fetchConnectionStatusInBackground_multipleDevices_connectedDevicesHasOnlyConnected() {
        val device1Connected =
            mock<CachedBluetoothDevice>().also { whenever(it.isConnected).thenReturn(true) }
        val device2Disconnected =
            mock<CachedBluetoothDevice>().also { whenever(it.isConnected).thenReturn(false) }
        val device3Connected =
            mock<CachedBluetoothDevice>().also { whenever(it.isConnected).thenReturn(true) }

        val status =
            fetchConnectionStatus(
                currentDevices = listOf(device1Connected, device2Disconnected, device3Connected)
            )

        assertThat(status.connectedDevices).isEqualTo(listOf(device1Connected, device3Connected))
    }

    private fun fetchConnectionStatus(
        currentDevices: Collection<CachedBluetoothDevice>
    ): ConnectionStatusModel {
        var receivedStatus: ConnectionStatusModel? = null
        underTest.fetchConnectionStatusInBackground(currentDevices) { status ->
            receivedStatus = status
        }
        scheduler.runCurrent()
        return receivedStatus!!
    }
}
