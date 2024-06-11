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
package com.android.systemui.bluetooth.qsdialog

import android.bluetooth.BluetoothDevice
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.settingslib.bluetooth.CachedBluetoothDevice
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.whenever
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceItemActionInteractorImplTest : SysuiTestCase() {
    @get:Rule val mockitoRule: MockitoRule = MockitoJUnit.rule()
    private val kosmos = testKosmos().apply { testDispatcher = UnconfinedTestDispatcher() }
    private lateinit var actionInteractorImpl: DeviceItemActionInteractor

    @Mock private lateinit var dialog: SystemUIDialog
    @Mock private lateinit var cachedDevice: CachedBluetoothDevice
    @Mock private lateinit var device: BluetoothDevice
    @Mock private lateinit var deviceItem: DeviceItem

    @Before
    fun setUp() {
        actionInteractorImpl = kosmos.deviceItemActionInteractor
        whenever(deviceItem.cachedBluetoothDevice).thenReturn(cachedDevice)
        whenever(cachedDevice.address).thenReturn("ADDRESS")
        whenever(cachedDevice.device).thenReturn(device)
    }

    @Test
    fun testOnClick_connectedMedia_setActive() {
        with(kosmos) {
            testScope.runTest {
                whenever(deviceItem.type)
                    .thenReturn(DeviceItemType.AVAILABLE_MEDIA_BLUETOOTH_DEVICE)
                actionInteractorImpl.onClick(deviceItem, dialog)
                verify(cachedDevice).setActive()
                verify(bluetoothTileDialogLogger)
                    .logDeviceClick(
                        cachedDevice.address,
                        DeviceItemType.AVAILABLE_MEDIA_BLUETOOTH_DEVICE
                    )
            }
        }
    }

    @Test
    fun testOnClick_activeMedia_disconnect() {
        with(kosmos) {
            testScope.runTest {
                whenever(deviceItem.type).thenReturn(DeviceItemType.ACTIVE_MEDIA_BLUETOOTH_DEVICE)
                actionInteractorImpl.onClick(deviceItem, dialog)
                verify(cachedDevice).disconnect()
                verify(bluetoothTileDialogLogger)
                    .logDeviceClick(
                        cachedDevice.address,
                        DeviceItemType.ACTIVE_MEDIA_BLUETOOTH_DEVICE
                    )
            }
        }
    }

    @Test
    fun testOnClick_connectedOtherDevice_disconnect() {
        with(kosmos) {
            testScope.runTest {
                whenever(deviceItem.type).thenReturn(DeviceItemType.CONNECTED_BLUETOOTH_DEVICE)
                actionInteractorImpl.onClick(deviceItem, dialog)
                verify(cachedDevice).disconnect()
                verify(bluetoothTileDialogLogger)
                    .logDeviceClick(cachedDevice.address, DeviceItemType.CONNECTED_BLUETOOTH_DEVICE)
            }
        }
    }

    @Test
    fun testOnClick_saved_connect() {
        with(kosmos) {
            testScope.runTest {
                whenever(deviceItem.type).thenReturn(DeviceItemType.SAVED_BLUETOOTH_DEVICE)
                actionInteractorImpl.onClick(deviceItem, dialog)
                verify(cachedDevice).connect()
                verify(bluetoothTileDialogLogger)
                    .logDeviceClick(cachedDevice.address, DeviceItemType.SAVED_BLUETOOTH_DEVICE)
            }
        }
    }
}
