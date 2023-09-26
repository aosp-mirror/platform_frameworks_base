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
class DeviceItemFactoryTest : SysuiTestCase() {

    @get:Rule val mockitoRule: MockitoRule = MockitoJUnit.rule()

    @Mock private lateinit var cachedDevice: CachedBluetoothDevice

    private val availableMediaDeviceItemFactory = AvailableMediaDeviceItemFactory()
    private val connectedDeviceItemFactory = ConnectedDeviceItemFactory()
    private val savedDeviceItemFactory = SavedDeviceItemFactory()

    @Before
    fun setup() {
        `when`(cachedDevice.name).thenReturn(DEVICE_NAME)
        `when`(cachedDevice.connectionSummary).thenReturn(CONNECTION_SUMMARY)
    }

    @Test
    fun testAvailableMediaDeviceItemFactory_createFromCachedDevice() {
        val deviceItem = availableMediaDeviceItemFactory.create(context, cachedDevice)

        assertDeviceItem(deviceItem, DeviceItemType.AVAILABLE_MEDIA_BLUETOOTH_DEVICE)
    }

    @Test
    fun testConnectedDeviceItemFactory_createFromCachedDevice() {
        val deviceItem = connectedDeviceItemFactory.create(context, cachedDevice)

        assertDeviceItem(deviceItem, DeviceItemType.CONNECTED_BLUETOOTH_DEVICE)
    }

    @Test
    fun testSavedDeviceItemFactory_createFromCachedDevice() {
        val deviceItem = savedDeviceItemFactory.create(context, cachedDevice)

        assertDeviceItem(deviceItem, DeviceItemType.SAVED_BLUETOOTH_DEVICE)
        assertThat(deviceItem.background).isNotNull()
    }

    private fun assertDeviceItem(deviceItem: DeviceItem?, deviceItemType: DeviceItemType) {
        assertThat(deviceItem).isNotNull()
        assertThat(deviceItem!!.type).isEqualTo(deviceItemType)
        assertThat(deviceItem.cachedBluetoothDevice).isEqualTo(cachedDevice)
        assertThat(deviceItem.deviceName).isEqualTo(DEVICE_NAME)
        assertThat(deviceItem.connectionSummary).isEqualTo(CONNECTION_SUMMARY)
    }

    companion object {
        const val DEVICE_NAME = "DeviceName"
        const val CONNECTION_SUMMARY = "ConnectionSummary"
    }
}
