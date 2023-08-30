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

import android.content.Context
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

    @Mock private lateinit var cachedDevice: CachedBluetoothDevice

    @Mock private lateinit var deviceItem1: DeviceItem

    @Mock private lateinit var deviceItem2: DeviceItem

    private lateinit var interactor: DeviceItemInteractor

    @Before
    fun setUp() {
        interactor = DeviceItemInteractor(bluetoothTileDialogRepository)
    }

    @Test
    fun testGetDeviceItems_noCachedDevice_returnEmpty() {
        `when`(bluetoothTileDialogRepository.cachedDevices).thenReturn(emptyList())
        interactor.setDeviceItemFactoryListForTesting(listOf(createFactory(true, deviceItem1)))

        val deviceItems = interactor.getDeviceItems(mContext)

        assertThat(deviceItems).isEmpty()
    }

    @Test
    fun testGetDeviceItems_hasCachedDevice_predicateNotMatch_returnEmpty() {
        `when`(bluetoothTileDialogRepository.cachedDevices).thenReturn(listOf(cachedDevice))
        interactor.setDeviceItemFactoryListForTesting(listOf(createFactory(false, deviceItem1)))

        val deviceItems = interactor.getDeviceItems(mContext)

        assertThat(deviceItems).isEmpty()
    }

    @Test
    fun testDeviceItems_hasCachedDevice_predicateMatch_returnDeviceItem() {
        `when`(bluetoothTileDialogRepository.cachedDevices).thenReturn(listOf(cachedDevice))
        interactor.setDeviceItemFactoryListForTesting(listOf(createFactory(true, deviceItem1)))

        var deviceItems = interactor.getDeviceItems(mContext)

        assertThat(deviceItems).hasSize(1)
        assertThat(deviceItems[0]).isEqualTo(deviceItem1)
    }

    @Test
    fun testDeviceItems_hasCachedDevice_predicateMatch_returnMultipleDeviceItem() {
        `when`(bluetoothTileDialogRepository.cachedDevices)
            .thenReturn(listOf(cachedDevice, cachedDevice))
        interactor.setDeviceItemFactoryListForTesting(
            listOf(createFactory(false, deviceItem1), createFactory(true, deviceItem2))
        )

        var deviceItems = interactor.getDeviceItems(mContext)

        assertThat(deviceItems).hasSize(2)
        assertThat(deviceItems[0]).isEqualTo(deviceItem2)
        assertThat(deviceItems[1]).isEqualTo(deviceItem2)
    }

    private fun createFactory(predicateResult: Boolean, deviceItem: DeviceItem): DeviceItemFactory {
        return object : DeviceItemFactory() {
            override fun predicate(cachedBluetoothDevice: CachedBluetoothDevice) = predicateResult

            override fun create(context: Context, cachedBluetoothDevice: CachedBluetoothDevice) =
                deviceItem
        }
    }
}
