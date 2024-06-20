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
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.AudioManager
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.settingslib.bluetooth.CachedBluetoothDevice
import com.android.settingslib.flags.Flags
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
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class DeviceItemFactoryTest : SysuiTestCase() {
    @get:Rule val mockitoRule: MockitoRule = MockitoJUnit.rule()

    @Mock private lateinit var cachedDevice: CachedBluetoothDevice
    @Mock private lateinit var bluetoothDevice: BluetoothDevice
    @Mock private lateinit var packageManager: PackageManager

    private val availableMediaDeviceItemFactory = AvailableMediaDeviceItemFactory()
    private val connectedDeviceItemFactory = ConnectedDeviceItemFactory()
    private val savedDeviceItemFactory = SavedDeviceItemFactory()

    private val audioManager = context.getSystemService(AudioManager::class.java)!!

    @Before
    fun setup() {
        `when`(cachedDevice.name).thenReturn(DEVICE_NAME)
        `when`(cachedDevice.address).thenReturn(DEVICE_ADDRESS)
        `when`(cachedDevice.device).thenReturn(bluetoothDevice)
        `when`(cachedDevice.connectionSummary).thenReturn(CONNECTION_SUMMARY)

        context.setMockPackageManager(packageManager)
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

    @Test
    @DisableFlags(Flags.FLAG_ENABLE_HIDE_EXCLUSIVELY_MANAGED_BLUETOOTH_DEVICE)
    fun testSavedFactory_isFilterMatched_bondedAndNotConnected_returnsTrue() {
        `when`(cachedDevice.bondState).thenReturn(BluetoothDevice.BOND_BONDED)
        `when`(cachedDevice.isConnected).thenReturn(false)

        assertThat(savedDeviceItemFactory.isFilterMatched(context, cachedDevice, audioManager))
            .isTrue()
    }

    @Test
    @DisableFlags(Flags.FLAG_ENABLE_HIDE_EXCLUSIVELY_MANAGED_BLUETOOTH_DEVICE)
    fun testSavedFactory_isFilterMatched_connected_returnsFalse() {
        `when`(cachedDevice.bondState).thenReturn(BluetoothDevice.BOND_BONDED)
        `when`(cachedDevice.isConnected).thenReturn(true)

        assertThat(savedDeviceItemFactory.isFilterMatched(context, cachedDevice, audioManager))
            .isFalse()
    }

    @Test
    @DisableFlags(Flags.FLAG_ENABLE_HIDE_EXCLUSIVELY_MANAGED_BLUETOOTH_DEVICE)
    fun testSavedFactory_isFilterMatched_notBonded_returnsFalse() {
        `when`(cachedDevice.bondState).thenReturn(BluetoothDevice.BOND_NONE)
        `when`(cachedDevice.isConnected).thenReturn(false)

        assertThat(savedDeviceItemFactory.isFilterMatched(context, cachedDevice, audioManager))
            .isFalse()
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_HIDE_EXCLUSIVELY_MANAGED_BLUETOOTH_DEVICE)
    fun testSavedFactory_isFilterMatched_exclusivelyManaged_returnsFalse() {
        `when`(bluetoothDevice.getMetadata(BluetoothDevice.METADATA_EXCLUSIVE_MANAGER))
            .thenReturn(TEST_EXCLUSIVE_MANAGER.toByteArray())
        `when`(packageManager.getApplicationInfo(TEST_EXCLUSIVE_MANAGER, 0))
            .thenReturn(ApplicationInfo())
        `when`(cachedDevice.bondState).thenReturn(BluetoothDevice.BOND_BONDED)
        `when`(cachedDevice.isConnected).thenReturn(false)

        assertThat(savedDeviceItemFactory.isFilterMatched(context, cachedDevice, audioManager))
            .isFalse()
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_HIDE_EXCLUSIVELY_MANAGED_BLUETOOTH_DEVICE)
    fun testSavedFactory_isFilterMatched_noExclusiveManager_returnsTrue() {
        `when`(cachedDevice.bondState).thenReturn(BluetoothDevice.BOND_BONDED)
        `when`(cachedDevice.isConnected).thenReturn(false)

        assertThat(savedDeviceItemFactory.isFilterMatched(context, cachedDevice, audioManager))
            .isTrue()
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_HIDE_EXCLUSIVELY_MANAGED_BLUETOOTH_DEVICE)
    fun testSavedFactory_isFilterMatched_exclusiveManagerNotEnabled_returnsTrue() {
        `when`(bluetoothDevice.getMetadata(BluetoothDevice.METADATA_EXCLUSIVE_MANAGER))
            .thenReturn(TEST_EXCLUSIVE_MANAGER.toByteArray())
        `when`(packageManager.getApplicationInfo(TEST_EXCLUSIVE_MANAGER, 0))
            .thenReturn(ApplicationInfo().also { it.enabled = false })
        `when`(cachedDevice.bondState).thenReturn(BluetoothDevice.BOND_BONDED)
        `when`(cachedDevice.isConnected).thenReturn(false)

        assertThat(savedDeviceItemFactory.isFilterMatched(context, cachedDevice, audioManager))
            .isTrue()
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_HIDE_EXCLUSIVELY_MANAGED_BLUETOOTH_DEVICE)
    fun testSavedFactory_isFilterMatched_exclusiveManagerNotInstalled_returnsTrue() {
        `when`(bluetoothDevice.getMetadata(BluetoothDevice.METADATA_EXCLUSIVE_MANAGER))
            .thenReturn(TEST_EXCLUSIVE_MANAGER.toByteArray())
        `when`(packageManager.getApplicationInfo(TEST_EXCLUSIVE_MANAGER, 0))
            .thenThrow(PackageManager.NameNotFoundException("Test!"))
        `when`(cachedDevice.bondState).thenReturn(BluetoothDevice.BOND_BONDED)
        `when`(cachedDevice.isConnected).thenReturn(false)

        assertThat(savedDeviceItemFactory.isFilterMatched(context, cachedDevice, audioManager))
            .isTrue()
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_HIDE_EXCLUSIVELY_MANAGED_BLUETOOTH_DEVICE)
    fun testSavedFactory_isFilterMatched_notExclusivelyManaged_notBonded_returnsFalse() {
        `when`(cachedDevice.bondState).thenReturn(BluetoothDevice.BOND_NONE)
        `when`(cachedDevice.isConnected).thenReturn(false)

        assertThat(savedDeviceItemFactory.isFilterMatched(context, cachedDevice, audioManager))
            .isFalse()
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_HIDE_EXCLUSIVELY_MANAGED_BLUETOOTH_DEVICE)
    fun testSavedFactory_isFilterMatched_notExclusivelyManaged_connected_returnsFalse() {
        `when`(cachedDevice.bondState).thenReturn(BluetoothDevice.BOND_BONDED)
        `when`(cachedDevice.isConnected).thenReturn(true)

        assertThat(savedDeviceItemFactory.isFilterMatched(context, cachedDevice, audioManager))
            .isFalse()
    }

    @Test
    @DisableFlags(Flags.FLAG_ENABLE_HIDE_EXCLUSIVELY_MANAGED_BLUETOOTH_DEVICE)
    fun testConnectedFactory_isFilterMatched_bondedAndConnected_returnsTrue() {
        `when`(bluetoothDevice.bondState).thenReturn(BluetoothDevice.BOND_BONDED)
        `when`(bluetoothDevice.isConnected).thenReturn(true)
        audioManager.setMode(AudioManager.MODE_NORMAL)

        assertThat(connectedDeviceItemFactory.isFilterMatched(context, cachedDevice, audioManager))
            .isTrue()
    }

    @Test
    @DisableFlags(Flags.FLAG_ENABLE_HIDE_EXCLUSIVELY_MANAGED_BLUETOOTH_DEVICE)
    fun testConnectedFactory_isFilterMatched_notConnected_returnsFalse() {
        `when`(bluetoothDevice.bondState).thenReturn(BluetoothDevice.BOND_BONDED)
        `when`(bluetoothDevice.isConnected).thenReturn(false)
        audioManager.setMode(AudioManager.MODE_NORMAL)

        assertThat(connectedDeviceItemFactory.isFilterMatched(context, cachedDevice, audioManager))
            .isFalse()
    }

    @Test
    @DisableFlags(Flags.FLAG_ENABLE_HIDE_EXCLUSIVELY_MANAGED_BLUETOOTH_DEVICE)
    fun testConnectedFactory_isFilterMatched_notBonded_returnsFalse() {
        `when`(bluetoothDevice.bondState).thenReturn(BluetoothDevice.BOND_NONE)
        `when`(bluetoothDevice.isConnected).thenReturn(true)
        audioManager.setMode(AudioManager.MODE_NORMAL)

        assertThat(connectedDeviceItemFactory.isFilterMatched(context, cachedDevice, audioManager))
            .isFalse()
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_HIDE_EXCLUSIVELY_MANAGED_BLUETOOTH_DEVICE)
    fun testConnectedFactory_isFilterMatched_exclusivelyManaged_returnsFalse() {
        `when`(bluetoothDevice.getMetadata(BluetoothDevice.METADATA_EXCLUSIVE_MANAGER))
            .thenReturn(TEST_EXCLUSIVE_MANAGER.toByteArray())
        `when`(packageManager.getApplicationInfo(TEST_EXCLUSIVE_MANAGER, 0))
            .thenReturn(ApplicationInfo())
        `when`(bluetoothDevice.bondState).thenReturn(BluetoothDevice.BOND_BONDED)
        `when`(bluetoothDevice.isConnected).thenReturn(true)
        audioManager.setMode(AudioManager.MODE_NORMAL)

        assertThat(connectedDeviceItemFactory.isFilterMatched(context, cachedDevice, audioManager))
            .isFalse()
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_HIDE_EXCLUSIVELY_MANAGED_BLUETOOTH_DEVICE)
    fun testConnectedFactory_isFilterMatched_noExclusiveManager_returnsTrue() {
        `when`(bluetoothDevice.bondState).thenReturn(BluetoothDevice.BOND_BONDED)
        `when`(bluetoothDevice.isConnected).thenReturn(true)
        audioManager.setMode(AudioManager.MODE_NORMAL)

        assertThat(connectedDeviceItemFactory.isFilterMatched(context, cachedDevice, audioManager))
            .isTrue()
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_HIDE_EXCLUSIVELY_MANAGED_BLUETOOTH_DEVICE)
    fun testConnectedFactory_isFilterMatched_exclusiveManagerNotEnabled_returnsTrue() {
        `when`(bluetoothDevice.getMetadata(BluetoothDevice.METADATA_EXCLUSIVE_MANAGER))
            .thenReturn(TEST_EXCLUSIVE_MANAGER.toByteArray())
        `when`(packageManager.getApplicationInfo(TEST_EXCLUSIVE_MANAGER, 0))
            .thenReturn(ApplicationInfo().also { it.enabled = false })
        `when`(bluetoothDevice.bondState).thenReturn(BluetoothDevice.BOND_BONDED)
        `when`(bluetoothDevice.isConnected).thenReturn(true)
        audioManager.setMode(AudioManager.MODE_NORMAL)

        assertThat(connectedDeviceItemFactory.isFilterMatched(context, cachedDevice, audioManager))
            .isTrue()
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_HIDE_EXCLUSIVELY_MANAGED_BLUETOOTH_DEVICE)
    fun testConnectedFactory_isFilterMatched_exclusiveManagerNotInstalled_returnsTrue() {
        `when`(bluetoothDevice.getMetadata(BluetoothDevice.METADATA_EXCLUSIVE_MANAGER))
            .thenReturn(TEST_EXCLUSIVE_MANAGER.toByteArray())
        `when`(packageManager.getApplicationInfo(TEST_EXCLUSIVE_MANAGER, 0))
            .thenThrow(PackageManager.NameNotFoundException("Test!"))
        `when`(bluetoothDevice.bondState).thenReturn(BluetoothDevice.BOND_BONDED)
        `when`(bluetoothDevice.isConnected).thenReturn(true)
        audioManager.setMode(AudioManager.MODE_NORMAL)

        assertThat(connectedDeviceItemFactory.isFilterMatched(context, cachedDevice, audioManager))
            .isTrue()
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_HIDE_EXCLUSIVELY_MANAGED_BLUETOOTH_DEVICE)
    fun testConnectedFactory_isFilterMatched_notExclusivelyManaged_notBonded_returnsFalse() {
        `when`(bluetoothDevice.bondState).thenReturn(BluetoothDevice.BOND_NONE)
        `when`(bluetoothDevice.isConnected).thenReturn(true)
        audioManager.setMode(AudioManager.MODE_NORMAL)

        assertThat(connectedDeviceItemFactory.isFilterMatched(context, cachedDevice, audioManager))
            .isFalse()
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_HIDE_EXCLUSIVELY_MANAGED_BLUETOOTH_DEVICE)
    fun testConnectedFactory_isFilterMatched_notExclusivelyManaged_notConnected_returnsFalse() {
        `when`(bluetoothDevice.bondState).thenReturn(BluetoothDevice.BOND_BONDED)
        `when`(bluetoothDevice.isConnected).thenReturn(false)
        audioManager.setMode(AudioManager.MODE_NORMAL)

        assertThat(connectedDeviceItemFactory.isFilterMatched(context, cachedDevice, audioManager))
            .isFalse()
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
        private const val TEST_EXCLUSIVE_MANAGER = "com.test.manager"
        private const val DEVICE_ADDRESS = "04:52:C7:0B:D8:3C"
    }
}
