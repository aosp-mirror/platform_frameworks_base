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
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.settingslib.bluetooth.CachedBluetoothDevice
import com.android.settingslib.bluetooth.CachedBluetoothDeviceManager
import com.android.settingslib.bluetooth.LocalBluetoothManager
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
class BluetoothTileDialogRepositoryTest : SysuiTestCase() {

    @get:Rule val mockitoRule: MockitoRule = MockitoJUnit.rule()

    @Mock private lateinit var localBluetoothManager: LocalBluetoothManager

    @Mock private lateinit var bluetoothAdapter: BluetoothAdapter

    @Mock private lateinit var cachedDeviceManager: CachedBluetoothDeviceManager

    @Mock private lateinit var cachedDevicesCopy: Collection<CachedBluetoothDevice>

    private lateinit var repository: BluetoothTileDialogRepository

    @Before
    fun setUp() {
        `when`(localBluetoothManager.cachedDeviceManager).thenReturn(cachedDeviceManager)
        `when`(cachedDeviceManager.cachedDevicesCopy).thenReturn(cachedDevicesCopy)

        repository = BluetoothTileDialogRepository(localBluetoothManager, bluetoothAdapter)
    }

    @Test
    fun testCachedDevices_bluetoothOff_emptyList() {
        `when`(bluetoothAdapter.isEnabled).thenReturn(false)

        val result = repository.cachedDevices

        assertThat(result).isEmpty()
    }

    @Test
    fun testCachedDevices_bluetoothOn_returnDevice() {
        `when`(bluetoothAdapter.isEnabled).thenReturn(true)

        val result = repository.cachedDevices

        assertThat(result).isEqualTo(cachedDevicesCopy)
    }
}
