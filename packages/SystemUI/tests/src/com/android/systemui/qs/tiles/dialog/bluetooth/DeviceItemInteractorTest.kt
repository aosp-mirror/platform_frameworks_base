/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.qs.tiles.dialog.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.media.AudioManager
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.settingslib.bluetooth.CachedBluetoothDevice
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class DeviceItemInteractorTest : SysuiTestCase() {

    @get:Rule val mockitoRule: MockitoRule = MockitoJUnit.rule()

    @Mock private lateinit var bluetoothTileDialogRepository: BluetoothTileDialogRepository

    @Mock private lateinit var cachedDevice1: CachedBluetoothDevice

    @Mock private lateinit var cachedDevice2: CachedBluetoothDevice

    @Mock private lateinit var device1: BluetoothDevice

    @Mock private lateinit var device2: BluetoothDevice

    @Mock private lateinit var deviceItem1: DeviceItem

    @Mock private lateinit var deviceItem2: DeviceItem

    @Mock private lateinit var audioManager: AudioManager

    @Mock private lateinit var adapter: BluetoothAdapter

    private lateinit var interactor: DeviceItemInteractor

    @Before
    fun setUp() {
        interactor = DeviceItemInteractor(bluetoothTileDialogRepository, audioManager, adapter)

        `when`(deviceItem1.cachedBluetoothDevice).thenReturn(cachedDevice1)
        `when`(deviceItem2.cachedBluetoothDevice).thenReturn(cachedDevice2)
        `when`(cachedDevice1.device).thenReturn(device1)
        `when`(cachedDevice2.device).thenReturn(device2)
        `when`(bluetoothTileDialogRepository.cachedDevices)
            .thenReturn(listOf(cachedDevice1, cachedDevice2))
    }

    @Test
    fun testGetDeviceItems_noCachedDevice_returnEmpty() {
        `when`(bluetoothTileDialogRepository.cachedDevices).thenReturn(emptyList())
        interactor.setDeviceItemFactoryListForTesting(listOf(createFactory({ true }, deviceItem1)))

        val deviceItems = interactor.getDeviceItems(mContext)

        assertThat(deviceItems).isEmpty()
    }

    @Test
    fun testGetDeviceItems_hasCachedDevice_filterNotMatch_returnEmpty() {
        `when`(bluetoothTileDialogRepository.cachedDevices).thenReturn(listOf(cachedDevice1))
        interactor.setDeviceItemFactoryListForTesting(listOf(createFactory({ false }, deviceItem1)))

        val deviceItems = interactor.getDeviceItems(mContext)

        assertThat(deviceItems).isEmpty()
    }

    @Test
    fun testGetDeviceItems_hasCachedDevice_filterMatch_returnDeviceItem() {
        `when`(bluetoothTileDialogRepository.cachedDevices).thenReturn(listOf(cachedDevice1))
        interactor.setDeviceItemFactoryListForTesting(listOf(createFactory({ true }, deviceItem1)))

        val deviceItems = interactor.getDeviceItems(mContext)

        assertThat(deviceItems).hasSize(1)
        assertThat(deviceItems[0]).isEqualTo(deviceItem1)
    }

    @Test
    fun testGetDeviceItems_hasCachedDevice_filterMatch_returnMultipleDeviceItem() {
        `when`(adapter.mostRecentlyConnectedDevices).thenReturn(null)
        interactor.setDeviceItemFactoryListForTesting(
            listOf(createFactory({ false }, deviceItem1), createFactory({ true }, deviceItem2))
        )

        val deviceItems = interactor.getDeviceItems(mContext)

        assertThat(deviceItems).hasSize(2)
        assertThat(deviceItems[0]).isEqualTo(deviceItem2)
        assertThat(deviceItems[1]).isEqualTo(deviceItem2)
    }

    @Test
    fun testGetDeviceItems_sortByDisplayPriority() {
        `when`(adapter.mostRecentlyConnectedDevices).thenReturn(null)
        interactor.setDeviceItemFactoryListForTesting(
            listOf(
                createFactory({ cachedDevice -> cachedDevice.device == device1 }, deviceItem1),
                createFactory({ cachedDevice -> cachedDevice.device == device2 }, deviceItem2)
            )
        )
        interactor.setDisplayPriorityForTesting(
            listOf(DeviceItemType.SAVED_BLUETOOTH_DEVICE, DeviceItemType.CONNECTED_BLUETOOTH_DEVICE)
        )
        `when`(deviceItem1.type).thenReturn(DeviceItemType.CONNECTED_BLUETOOTH_DEVICE)
        `when`(deviceItem2.type).thenReturn(DeviceItemType.SAVED_BLUETOOTH_DEVICE)

        val deviceItems = interactor.getDeviceItems(mContext)

        assertThat(deviceItems).isEqualTo(listOf(deviceItem2, deviceItem1))
    }

    @Test
    fun testGetDeviceItems_sameType_sortByRecentlyConnected() {
        `when`(adapter.mostRecentlyConnectedDevices).thenReturn(listOf(device2, device1))
        interactor.setDeviceItemFactoryListForTesting(
            listOf(
                createFactory({ cachedDevice -> cachedDevice.device == device1 }, deviceItem1),
                createFactory({ cachedDevice -> cachedDevice.device == device2 }, deviceItem2)
            )
        )
        interactor.setDisplayPriorityForTesting(
            listOf(DeviceItemType.CONNECTED_BLUETOOTH_DEVICE)
        )
        `when`(deviceItem1.type).thenReturn(DeviceItemType.CONNECTED_BLUETOOTH_DEVICE)
        `when`(deviceItem2.type).thenReturn(DeviceItemType.CONNECTED_BLUETOOTH_DEVICE)

        val deviceItems = interactor.getDeviceItems(mContext)

        assertThat(deviceItems).isEqualTo(listOf(deviceItem2, deviceItem1))
    }

    private fun createFactory(
        isFilterMatchFunc: (CachedBluetoothDevice) -> Boolean,
        deviceItem: DeviceItem
    ): DeviceItemFactory {
        return object : DeviceItemFactory() {
            override fun isFilterMatched(
                cachedDevice: CachedBluetoothDevice,
                audioManager: AudioManager?
            ) = isFilterMatchFunc(cachedDevice)

            override fun create(context: Context, cachedDevice: CachedBluetoothDevice) = deviceItem
        }
    }
}
