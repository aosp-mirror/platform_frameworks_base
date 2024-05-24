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
import com.android.internal.logging.UiEventLogger
import com.android.settingslib.bluetooth.CachedBluetoothDevice
import com.android.settingslib.bluetooth.LocalBluetoothManager
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verify
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

    @Mock private lateinit var localBluetoothManager: LocalBluetoothManager

    @Mock private lateinit var uiEventLogger: UiEventLogger

    @Mock private lateinit var logger: BluetoothTileDialogLogger

    private val fakeSystemClock = FakeSystemClock()

    private lateinit var interactor: DeviceItemInteractor

    private lateinit var dispatcher: CoroutineDispatcher

    private lateinit var testScope: TestScope

    @Before
    fun setUp() {
        dispatcher = UnconfinedTestDispatcher()
        testScope = TestScope(dispatcher)
        interactor =
            DeviceItemInteractor(
                bluetoothTileDialogRepository,
                audioManager,
                adapter,
                localBluetoothManager,
                fakeSystemClock,
                uiEventLogger,
                logger,
                testScope.backgroundScope,
                dispatcher
            )

        `when`(deviceItem1.cachedBluetoothDevice).thenReturn(cachedDevice1)
        `when`(deviceItem2.cachedBluetoothDevice).thenReturn(cachedDevice2)
        `when`(cachedDevice1.address).thenReturn("ADDRESS")
        `when`(cachedDevice1.device).thenReturn(device1)
        `when`(cachedDevice2.device).thenReturn(device2)
        `when`(bluetoothTileDialogRepository.cachedDevices)
            .thenReturn(listOf(cachedDevice1, cachedDevice2))
    }

    @Test
    fun testUpdateDeviceItems_noCachedDevice_returnEmpty() {
        testScope.runTest {
            `when`(bluetoothTileDialogRepository.cachedDevices).thenReturn(emptyList())
            interactor.setDeviceItemFactoryListForTesting(
                listOf(createFactory({ true }, deviceItem1))
            )

            val latest by collectLastValue(interactor.deviceItemUpdate)
            interactor.updateDeviceItems(mContext, DeviceFetchTrigger.FIRST_LOAD)

            assertThat(latest).isEqualTo(emptyList<DeviceItem>())
        }
    }

    @Test
    fun testUpdateDeviceItems_hasCachedDevice_filterNotMatch_returnEmpty() {
        testScope.runTest {
            `when`(bluetoothTileDialogRepository.cachedDevices).thenReturn(listOf(cachedDevice1))
            interactor.setDeviceItemFactoryListForTesting(
                listOf(createFactory({ false }, deviceItem1))
            )

            val latest by collectLastValue(interactor.deviceItemUpdate)
            interactor.updateDeviceItems(mContext, DeviceFetchTrigger.FIRST_LOAD)

            assertThat(latest).isEqualTo(emptyList<DeviceItem>())
        }
    }

    @Test
    fun testUpdateDeviceItems_hasCachedDevice_filterMatch_returnDeviceItem() {
        testScope.runTest {
            `when`(bluetoothTileDialogRepository.cachedDevices).thenReturn(listOf(cachedDevice1))
            interactor.setDeviceItemFactoryListForTesting(
                listOf(createFactory({ true }, deviceItem1))
            )

            val latest by collectLastValue(interactor.deviceItemUpdate)
            interactor.updateDeviceItems(mContext, DeviceFetchTrigger.FIRST_LOAD)

            assertThat(latest).isEqualTo(listOf(deviceItem1))
        }
    }

    @Test
    fun testUpdateDeviceItems_hasCachedDevice_filterMatch_returnMultipleDeviceItem() {
        testScope.runTest {
            `when`(adapter.mostRecentlyConnectedDevices).thenReturn(null)
            interactor.setDeviceItemFactoryListForTesting(
                listOf(createFactory({ false }, deviceItem1), createFactory({ true }, deviceItem2))
            )

            val latest by collectLastValue(interactor.deviceItemUpdate)
            interactor.updateDeviceItems(mContext, DeviceFetchTrigger.FIRST_LOAD)

            assertThat(latest).isEqualTo(listOf(deviceItem2, deviceItem2))
        }
    }

    @Test
    fun testUpdateDeviceItems_sortByDisplayPriority() {
        testScope.runTest {
            `when`(adapter.mostRecentlyConnectedDevices).thenReturn(null)
            interactor.setDeviceItemFactoryListForTesting(
                listOf(
                    createFactory({ cachedDevice -> cachedDevice.device == device1 }, deviceItem1),
                    createFactory({ cachedDevice -> cachedDevice.device == device2 }, deviceItem2)
                )
            )
            interactor.setDisplayPriorityForTesting(
                listOf(
                    DeviceItemType.SAVED_BLUETOOTH_DEVICE,
                    DeviceItemType.CONNECTED_BLUETOOTH_DEVICE
                )
            )
            `when`(deviceItem1.type).thenReturn(DeviceItemType.CONNECTED_BLUETOOTH_DEVICE)
            `when`(deviceItem2.type).thenReturn(DeviceItemType.SAVED_BLUETOOTH_DEVICE)

            val latest by collectLastValue(interactor.deviceItemUpdate)
            interactor.updateDeviceItems(mContext, DeviceFetchTrigger.FIRST_LOAD)

            assertThat(latest).isEqualTo(listOf(deviceItem2, deviceItem1))
        }
    }

    @Test
    fun testUpdateDeviceItems_sameType_sortByRecentlyConnected() {
        testScope.runTest {
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

            val latest by collectLastValue(interactor.deviceItemUpdate)
            interactor.updateDeviceItems(mContext, DeviceFetchTrigger.FIRST_LOAD)

            assertThat(latest).isEqualTo(listOf(deviceItem2, deviceItem1))
        }
    }

    @Test
    fun testUpdateDeviceItemOnClick_connectedMedia_setActive() {
        testScope.runTest {
            `when`(deviceItem1.type).thenReturn(DeviceItemType.AVAILABLE_MEDIA_BLUETOOTH_DEVICE)

            interactor.updateDeviceItemOnClick(deviceItem1)

            verify(cachedDevice1).setActive()
            verify(logger)
                .logDeviceClick(
                    cachedDevice1.address,
                    DeviceItemType.AVAILABLE_MEDIA_BLUETOOTH_DEVICE
                )
        }
    }

    @Test
    fun testUpdateDeviceItemOnClick_activeMedia_disconnect() {
        testScope.runTest {
            `when`(deviceItem1.type).thenReturn(DeviceItemType.ACTIVE_MEDIA_BLUETOOTH_DEVICE)

            interactor.updateDeviceItemOnClick(deviceItem1)

            verify(cachedDevice1).disconnect()
            verify(logger)
                .logDeviceClick(cachedDevice1.address, DeviceItemType.ACTIVE_MEDIA_BLUETOOTH_DEVICE)
        }
    }

    @Test
    fun testUpdateDeviceItemOnClick_connectedOtherDevice_disconnect() {
        testScope.runTest {
            `when`(deviceItem1.type).thenReturn(DeviceItemType.CONNECTED_BLUETOOTH_DEVICE)

            interactor.updateDeviceItemOnClick(deviceItem1)

            verify(cachedDevice1).disconnect()
            verify(logger)
                .logDeviceClick(cachedDevice1.address, DeviceItemType.CONNECTED_BLUETOOTH_DEVICE)
        }
    }

    @Test
    fun testUpdateDeviceItemOnClick_saved_connect() {
        testScope.runTest {
            `when`(deviceItem1.type).thenReturn(DeviceItemType.SAVED_BLUETOOTH_DEVICE)

            interactor.updateDeviceItemOnClick(deviceItem1)

            verify(cachedDevice1).connect()
            verify(logger)
                .logDeviceClick(cachedDevice1.address, DeviceItemType.SAVED_BLUETOOTH_DEVICE)
        }
    }

    private fun createFactory(
        isFilterMatchFunc: (CachedBluetoothDevice) -> Boolean,
        deviceItem: DeviceItem
    ): DeviceItemFactory {
        return object : DeviceItemFactory() {
            override fun isFilterMatched(
                context: Context,
                cachedDevice: CachedBluetoothDevice,
                audioManager: AudioManager?
            ) = isFilterMatchFunc(cachedDevice)

            override fun create(context: Context, cachedDevice: CachedBluetoothDevice) = deviceItem
        }
    }
}
