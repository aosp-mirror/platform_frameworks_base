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

import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
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
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class DeviceItemActionInteractorTest : SysuiTestCase() {
    @get:Rule val mockitoRule: MockitoRule = MockitoJUnit.rule()
    private val kosmos = testKosmos().apply { testDispatcher = UnconfinedTestDispatcher() }
    private lateinit var actionInteractorImpl: DeviceItemActionInteractor
    private lateinit var activeMediaDeviceItem: DeviceItem
    private lateinit var notConnectedDeviceItem: DeviceItem
    private lateinit var connectedMediaDeviceItem: DeviceItem
    private lateinit var connectedOtherDeviceItem: DeviceItem
    private lateinit var audioSharingDeviceItem: DeviceItem
    @Mock private lateinit var dialog: SystemUIDialog

    @Before
    fun setUp() {
        activeMediaDeviceItem =
            DeviceItem(
                type = DeviceItemType.ACTIVE_MEDIA_BLUETOOTH_DEVICE,
                cachedBluetoothDevice = kosmos.cachedBluetoothDevice,
                deviceName = DEVICE_NAME,
                connectionSummary = DEVICE_CONNECTION_SUMMARY,
                iconWithDescription = null,
                background = null,
            )
        notConnectedDeviceItem =
            DeviceItem(
                type = DeviceItemType.SAVED_BLUETOOTH_DEVICE,
                cachedBluetoothDevice = kosmos.cachedBluetoothDevice,
                deviceName = DEVICE_NAME,
                connectionSummary = DEVICE_CONNECTION_SUMMARY,
                iconWithDescription = null,
                background = null,
            )
        connectedMediaDeviceItem =
            DeviceItem(
                type = DeviceItemType.AVAILABLE_MEDIA_BLUETOOTH_DEVICE,
                cachedBluetoothDevice = kosmos.cachedBluetoothDevice,
                deviceName = DEVICE_NAME,
                connectionSummary = DEVICE_CONNECTION_SUMMARY,
                iconWithDescription = null,
                background = null,
            )
        connectedOtherDeviceItem =
            DeviceItem(
                type = DeviceItemType.CONNECTED_BLUETOOTH_DEVICE,
                cachedBluetoothDevice = kosmos.cachedBluetoothDevice,
                deviceName = DEVICE_NAME,
                connectionSummary = DEVICE_CONNECTION_SUMMARY,
                iconWithDescription = null,
                background = null,
            )
        audioSharingDeviceItem =
            DeviceItem(
                type = DeviceItemType.AUDIO_SHARING_MEDIA_BLUETOOTH_DEVICE,
                cachedBluetoothDevice = kosmos.cachedBluetoothDevice,
                deviceName = DEVICE_NAME,
                connectionSummary = DEVICE_CONNECTION_SUMMARY,
                iconWithDescription = null,
                background = null,
            )
        actionInteractorImpl = kosmos.deviceItemActionInteractorImpl
    }

    @Test
    fun testOnClick_connectedMedia_setActive() {
        with(kosmos) {
            testScope.runTest {
                whenever(cachedBluetoothDevice.address).thenReturn(DEVICE_ADDRESS)
                actionInteractorImpl.onClick(connectedMediaDeviceItem, dialog)
                verify(cachedBluetoothDevice).setActive()
            }
        }
    }

    @Test
    fun testOnClick_activeMedia_disconnect() {
        with(kosmos) {
            testScope.runTest {
                whenever(cachedBluetoothDevice.address).thenReturn(DEVICE_ADDRESS)
                actionInteractorImpl.onClick(activeMediaDeviceItem, dialog)
                verify(cachedBluetoothDevice).disconnect()
            }
        }
    }

    @Test
    fun testOnClick_connectedOtherDevice_disconnect() {
        with(kosmos) {
            testScope.runTest {
                whenever(cachedBluetoothDevice.address).thenReturn(DEVICE_ADDRESS)
                actionInteractorImpl.onClick(connectedOtherDeviceItem, dialog)
                verify(cachedBluetoothDevice).disconnect()
            }
        }
    }

    @Test
    fun testOnClick_saved_connect() {
        with(kosmos) {
            testScope.runTest {
                whenever(cachedBluetoothDevice.address).thenReturn(DEVICE_ADDRESS)
                actionInteractorImpl.onClick(notConnectedDeviceItem, dialog)
                verify(cachedBluetoothDevice).connect()
            }
        }
    }

    @Test
    fun onActionIconClick_onIntent() {
        with(kosmos) {
            testScope.runTest {
                var onIntentCalledOnAddress = ""
                whenever(cachedBluetoothDevice.address).thenReturn(DEVICE_ADDRESS)
                actionInteractorImpl.onActionIconClick(connectedMediaDeviceItem) {
                    onIntentCalledOnAddress = connectedMediaDeviceItem.cachedBluetoothDevice.address
                }
                assertThat(onIntentCalledOnAddress).isEqualTo(DEVICE_ADDRESS)
            }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun onActionIconClick_audioSharingDeviceType_throwException() {
        with(kosmos) {
            testScope.runTest {
                actionInteractorImpl.onActionIconClick(audioSharingDeviceItem) {}
            }
        }
    }

    private companion object {
        const val DEVICE_NAME = "device"
        const val DEVICE_CONNECTION_SUMMARY = "active"
        const val DEVICE_ADDRESS = "address"
    }
}
