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
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.testing.TestableLooper
import android.util.Pair
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession
import com.android.dx.mockito.inline.extended.StaticMockitoSession
import com.android.settingslib.bluetooth.BluetoothUtils
import com.android.settingslib.bluetooth.CachedBluetoothDevice
import com.android.settingslib.bluetooth.LocalBluetoothManager
import com.android.settingslib.flags.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.res.R
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.kotlin.any

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class DeviceItemFactoryTest : SysuiTestCase() {
    @get:Rule val mockitoRule: MockitoRule = MockitoJUnit.rule()

    private lateinit var mockitoSession: StaticMockitoSession
    @Mock private lateinit var cachedDevice: CachedBluetoothDevice
    @Mock private lateinit var bluetoothDevice: BluetoothDevice
    @Mock private lateinit var localBluetoothManager: LocalBluetoothManager
    @Mock private lateinit var drawable: Drawable

    private val availableMediaDeviceItemFactory = AvailableMediaDeviceItemFactory()
    private val connectedDeviceItemFactory = ConnectedDeviceItemFactory()
    private val savedDeviceItemFactory = SavedDeviceItemFactory()

    private val audioManager = context.getSystemService(AudioManager::class.java)!!

    @Before
    fun setup() {
        mockitoSession =
            mockitoSession().initMocks(this).mockStatic(BluetoothUtils::class.java).startMocking()
    }

    @After
    fun tearDown() {
        mockitoSession.finishMocking()
    }

    @Test
    fun testAvailableMediaDeviceItemFactory_createFromCachedDevice() {
        `when`(cachedDevice.name).thenReturn(DEVICE_NAME)
        `when`(cachedDevice.connectionSummary).thenReturn(CONNECTION_SUMMARY)
        `when`(BluetoothUtils.getBtClassDrawableWithDescription(any(), any()))
            .thenReturn(Pair.create(drawable, ""))
        val deviceItem = availableMediaDeviceItemFactory.create(context, cachedDevice)

        assertDeviceItem(deviceItem, DeviceItemType.AVAILABLE_MEDIA_BLUETOOTH_DEVICE)
    }

    @Test
    fun testConnectedDeviceItemFactory_createFromCachedDevice() {
        `when`(cachedDevice.name).thenReturn(DEVICE_NAME)
        `when`(cachedDevice.connectionSummary).thenReturn(CONNECTION_SUMMARY)
        `when`(BluetoothUtils.getBtClassDrawableWithDescription(any(), any()))
            .thenReturn(Pair.create(drawable, ""))
        val deviceItem = connectedDeviceItemFactory.create(context, cachedDevice)

        assertDeviceItem(deviceItem, DeviceItemType.CONNECTED_BLUETOOTH_DEVICE)
    }

    @Test
    fun testSavedDeviceItemFactory_createFromCachedDevice() {
        `when`(cachedDevice.name).thenReturn(DEVICE_NAME)
        `when`(cachedDevice.connectionSummary).thenReturn(CONNECTION_SUMMARY)
        `when`(BluetoothUtils.getBtClassDrawableWithDescription(any(), any()))
            .thenReturn(Pair.create(drawable, ""))
        val deviceItem = savedDeviceItemFactory.create(context, cachedDevice)

        assertDeviceItem(deviceItem, DeviceItemType.SAVED_BLUETOOTH_DEVICE)
        assertThat(deviceItem.background).isNotNull()
    }

    @Test
    fun testAvailableAudioSharingMediaDeviceItemFactory_createFromCachedDevice() {
        `when`(cachedDevice.name).thenReturn(DEVICE_NAME)
        `when`(BluetoothUtils.getBtClassDrawableWithDescription(any(), any()))
            .thenReturn(Pair.create(drawable, ""))
        val deviceItem =
            AvailableAudioSharingMediaDeviceItemFactory(localBluetoothManager)
                .create(context, cachedDevice)

        assertThat(deviceItem).isNotNull()
        assertThat(deviceItem.type)
            .isEqualTo(DeviceItemType.AVAILABLE_AUDIO_SHARING_MEDIA_BLUETOOTH_DEVICE)
        assertThat(deviceItem.cachedBluetoothDevice).isEqualTo(cachedDevice)
        assertThat(deviceItem.deviceName).isEqualTo(DEVICE_NAME)
        assertThat(deviceItem.isActive).isFalse()
        assertThat(deviceItem.connectionSummary)
            .isEqualTo(
                context.getString(
                    R.string.quick_settings_bluetooth_device_audio_sharing_or_switch_active
                )
            )
    }

    @Test
    fun testAvailableAudioSharingMediaDeviceItemFactory_isFilterMatched_flagOff_returnsFalse() {
         // Flags.FLAG_ENABLE_LE_AUDIO_SHARING off or the device doesn't support broadcast
         // source or assistant.
        `when`(BluetoothUtils.isAudioSharingEnabled()).thenReturn(false)

        assertThat(
                AvailableAudioSharingMediaDeviceItemFactory(localBluetoothManager)
                    .isFilterMatched(context, cachedDevice, audioManager)
            )
            .isFalse()
    }

    @Test
    fun testAvailableAudioSharingMediaDeviceItemFactory_isFilterMatched_isActiveDevice_returnsFalse() {
         // Flags.FLAG_ENABLE_LE_AUDIO_SHARING on and the device support broadcast source and
         // assistant.
        `when`(BluetoothUtils.isAudioSharingEnabled()).thenReturn(true)
        `when`(BluetoothUtils.isActiveMediaDevice(any())).thenReturn(true)

        assertThat(
                AvailableAudioSharingMediaDeviceItemFactory(localBluetoothManager)
                    .isFilterMatched(context, cachedDevice, audioManager)
            )
            .isFalse()
    }

    @Test
    fun testAvailableAudioSharingMediaDeviceItemFactory_isFilterMatched_isNotAvailable_returnsFalse() {
         // Flags.FLAG_ENABLE_LE_AUDIO_SHARING on and the device support broadcast source and
         // assistant.
        `when`(BluetoothUtils.isAudioSharingEnabled()).thenReturn(true)
        `when`(BluetoothUtils.isActiveMediaDevice(any())).thenReturn(false)
        `when`(BluetoothUtils.isAvailableMediaBluetoothDevice(any(), any())).thenReturn(true)
        `when`(BluetoothUtils.isAvailableAudioSharingMediaBluetoothDevice(any(), any()))
            .thenReturn(false)

        assertThat(
                AvailableAudioSharingMediaDeviceItemFactory(localBluetoothManager)
                    .isFilterMatched(context, cachedDevice, audioManager)
            )
            .isFalse()
    }

    @Test
    fun testAvailableAudioSharingMediaDeviceItemFactory_isFilterMatched_returnsTrue() {
         // Flags.FLAG_ENABLE_LE_AUDIO_SHARING on and the device support broadcast source and
         // assistant.
        `when`(BluetoothUtils.isAudioSharingEnabled()).thenReturn(true)
        `when`(BluetoothUtils.isActiveMediaDevice(any())).thenReturn(false)
        `when`(BluetoothUtils.isAvailableMediaBluetoothDevice(any(), any())).thenReturn(true)
        `when`(BluetoothUtils.isAvailableAudioSharingMediaBluetoothDevice(any(), any()))
            .thenReturn(true)

        assertThat(
                AvailableAudioSharingMediaDeviceItemFactory(localBluetoothManager)
                    .isFilterMatched(context, cachedDevice, audioManager)
            )
            .isTrue()
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

        assertThat(savedDeviceItemFactory.isFilterMatched(context, cachedDevice, audioManager))
            .isFalse()
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_HIDE_EXCLUSIVELY_MANAGED_BLUETOOTH_DEVICE)
    fun testSavedFactory_isFilterMatched_exclusivelyManaged_returnsFalse() {
        `when`(cachedDevice.device).thenReturn(bluetoothDevice)
        `when`(BluetoothUtils.isExclusivelyManagedBluetoothDevice(any(), any())).thenReturn(true)

        assertThat(savedDeviceItemFactory.isFilterMatched(context, cachedDevice, audioManager))
            .isFalse()
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_HIDE_EXCLUSIVELY_MANAGED_BLUETOOTH_DEVICE)
    fun testSavedFactory_isFilterMatched_notExclusiveManaged_returnsTrue() {
        `when`(cachedDevice.device).thenReturn(bluetoothDevice)
        `when`(BluetoothUtils.isExclusivelyManagedBluetoothDevice(any(), any())).thenReturn(false)
        `when`(cachedDevice.bondState).thenReturn(BluetoothDevice.BOND_BONDED)
        `when`(cachedDevice.isConnected).thenReturn(false)

        assertThat(savedDeviceItemFactory.isFilterMatched(context, cachedDevice, audioManager))
            .isTrue()
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_HIDE_EXCLUSIVELY_MANAGED_BLUETOOTH_DEVICE)
    fun testSavedFactory_isFilterMatched_notExclusivelyManaged_connected_returnsFalse() {
        `when`(cachedDevice.device).thenReturn(bluetoothDevice)
        `when`(BluetoothUtils.isExclusivelyManagedBluetoothDevice(any(), any())).thenReturn(false)
        `when`(cachedDevice.bondState).thenReturn(BluetoothDevice.BOND_BONDED)
        `when`(cachedDevice.isConnected).thenReturn(true)

        assertThat(savedDeviceItemFactory.isFilterMatched(context, cachedDevice, audioManager))
            .isFalse()
    }

    @Test
    @DisableFlags(Flags.FLAG_ENABLE_HIDE_EXCLUSIVELY_MANAGED_BLUETOOTH_DEVICE)
    fun testConnectedFactory_isFilterMatched_bondedAndConnected_returnsTrue() {
        `when`(BluetoothUtils.isConnectedBluetoothDevice(any(), any())).thenReturn(true)

        assertThat(connectedDeviceItemFactory.isFilterMatched(context, cachedDevice, audioManager))
            .isTrue()
    }

    @Test
    @DisableFlags(Flags.FLAG_ENABLE_HIDE_EXCLUSIVELY_MANAGED_BLUETOOTH_DEVICE)
    fun testConnectedFactory_isFilterMatched_notConnected_returnsFalse() {
        assertThat(connectedDeviceItemFactory.isFilterMatched(context, cachedDevice, audioManager))
            .isFalse()
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_HIDE_EXCLUSIVELY_MANAGED_BLUETOOTH_DEVICE)
    fun testConnectedFactory_isFilterMatched_exclusivelyManaged_returnsFalse() {
        `when`(cachedDevice.device).thenReturn(bluetoothDevice)
        `when`(BluetoothUtils.isExclusivelyManagedBluetoothDevice(any(), any())).thenReturn(true)

        assertThat(connectedDeviceItemFactory.isFilterMatched(context, cachedDevice, audioManager))
            .isFalse()
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_HIDE_EXCLUSIVELY_MANAGED_BLUETOOTH_DEVICE)
    fun testConnectedFactory_isFilterMatched_noExclusiveManager_returnsTrue() {
        `when`(cachedDevice.device).thenReturn(bluetoothDevice)
        `when`(BluetoothUtils.isExclusivelyManagedBluetoothDevice(any(), any())).thenReturn(false)
        `when`(BluetoothUtils.isConnectedBluetoothDevice(any(), any())).thenReturn(true)

        assertThat(connectedDeviceItemFactory.isFilterMatched(context, cachedDevice, audioManager))
            .isTrue()
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_HIDE_EXCLUSIVELY_MANAGED_BLUETOOTH_DEVICE)
    fun testConnectedFactory_isFilterMatched_notExclusivelyManaged_notConnected_returnsFalse() {
        `when`(cachedDevice.device).thenReturn(bluetoothDevice)
        `when`(BluetoothUtils.isExclusivelyManagedBluetoothDevice(any(), any())).thenReturn(false)
        `when`(BluetoothUtils.isConnectedBluetoothDevice(any(), any())).thenReturn(false)

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
    }
}
