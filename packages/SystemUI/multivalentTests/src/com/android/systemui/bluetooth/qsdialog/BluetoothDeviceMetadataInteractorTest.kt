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

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.settingslib.bluetooth.CachedBluetoothDevice
import com.android.systemui.SysuiTestCase
import com.android.systemui.bluetooth.bluetoothAdapter
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@SmallTest
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class BluetoothDeviceMetadataInteractorTest : SysuiTestCase() {
    @get:Rule val mockitoRule: MockitoRule = MockitoJUnit.rule()

    private val kosmos = testKosmos().apply { testDispatcher = UnconfinedTestDispatcher() }

    private val deviceItemUpdate: MutableSharedFlow<List<DeviceItem>> = MutableSharedFlow()
    @Mock private lateinit var cachedDevice1: CachedBluetoothDevice
    @Mock private lateinit var bluetoothDevice1: BluetoothDevice
    @Mock private lateinit var cachedDevice2: CachedBluetoothDevice
    @Mock private lateinit var bluetoothDevice2: BluetoothDevice
    @Captor
    private lateinit var argumentCaptor: ArgumentCaptor<BluetoothAdapter.OnMetadataChangedListener>
    private lateinit var interactor: BluetoothDeviceMetadataInteractor

    @Before
    fun setUp() {
        with(kosmos) {
            whenever(deviceItemInteractor.deviceItemUpdate).thenReturn(deviceItemUpdate)

            whenever(cachedDevice1.device).thenReturn(bluetoothDevice1)
            whenever(cachedDevice1.name).thenReturn(DEVICE_NAME)
            whenever(cachedDevice1.address).thenReturn(DEVICE_ADDRESS)
            whenever(cachedDevice1.connectionSummary).thenReturn(CONNECTION_SUMMARY)
            whenever(bluetoothDevice1.address).thenReturn(DEVICE_ADDRESS)

            whenever(cachedDevice2.device).thenReturn(bluetoothDevice2)
            whenever(cachedDevice2.name).thenReturn(DEVICE_NAME)
            whenever(cachedDevice2.address).thenReturn(DEVICE_ADDRESS)
            whenever(cachedDevice2.connectionSummary).thenReturn(CONNECTION_SUMMARY)
            whenever(bluetoothDevice2.address).thenReturn(DEVICE_ADDRESS)

            interactor = bluetoothDeviceMetadataInteractor
        }
    }

    @Test
    fun deviceItemUpdateEmpty_doNothing() {
        with(kosmos) {
            testScope.runTest {
                val update by collectLastValue(interactor.metadataUpdate)
                deviceItemUpdate.emit(emptyList())
                runCurrent()

                assertThat(update).isNull()
                verify(bluetoothAdapter, never()).addOnMetadataChangedListener(any(), any(), any())
                verify(bluetoothAdapter, never()).removeOnMetadataChangedListener(any(), any())
            }
        }
    }

    @Test
    fun deviceItemUpdate_registerListener() {
        with(kosmos) {
            testScope.runTest {
                val deviceItem = AvailableMediaDeviceItemFactory().create(context, cachedDevice1)
                val update by collectLastValue(interactor.metadataUpdate)
                deviceItemUpdate.emit(listOf(deviceItem))
                runCurrent()

                assertThat(update).isNull()
                verify(bluetoothAdapter)
                    .addOnMetadataChangedListener(eq(bluetoothDevice1), any(), any())
                verify(bluetoothAdapter, never()).removeOnMetadataChangedListener(any(), any())
            }
        }
    }

    @Test
    fun deviceItemUpdate_sameDeviceItems_registerListenerOnce() {
        with(kosmos) {
            testScope.runTest {
                val deviceItem = AvailableMediaDeviceItemFactory().create(context, cachedDevice1)
                val update by collectLastValue(interactor.metadataUpdate)
                deviceItemUpdate.emit(listOf(deviceItem))
                deviceItemUpdate.emit(listOf(deviceItem))
                runCurrent()

                assertThat(update).isNull()
                verify(bluetoothAdapter)
                    .addOnMetadataChangedListener(eq(bluetoothDevice1), any(), any())
                verify(bluetoothAdapter, never()).removeOnMetadataChangedListener(any(), any())
            }
        }
    }

    @Test
    fun deviceItemUpdate_differentDeviceItems_unregisterOldAndRegisterNew() {
        with(kosmos) {
            testScope.runTest {
                val deviceItem1 = AvailableMediaDeviceItemFactory().create(context, cachedDevice1)
                val deviceItem2 = AvailableMediaDeviceItemFactory().create(context, cachedDevice2)
                val update by collectLastValue(interactor.metadataUpdate)
                deviceItemUpdate.emit(listOf(deviceItem1))
                deviceItemUpdate.emit(listOf(deviceItem1, deviceItem2))
                runCurrent()

                assertThat(update).isNull()
                verify(bluetoothAdapter, times(2))
                    .addOnMetadataChangedListener(eq(bluetoothDevice1), any(), any())
                verify(bluetoothAdapter)
                    .addOnMetadataChangedListener(eq(bluetoothDevice2), any(), any())
                verify(bluetoothAdapter)
                    .removeOnMetadataChangedListener(eq(bluetoothDevice1), any())
            }
        }
    }

    @Test
    fun metadataUpdate_triggerCallback_emit() {
        with(kosmos) {
            testScope.runTest {
                val deviceItem = AvailableMediaDeviceItemFactory().create(context, cachedDevice1)
                val update by collectLastValue(interactor.metadataUpdate)
                deviceItemUpdate.emit(listOf(deviceItem))
                runCurrent()

                assertThat(update).isNull()
                verify(bluetoothAdapter)
                    .addOnMetadataChangedListener(
                        eq(bluetoothDevice1),
                        any(),
                        argumentCaptor.capture()
                    )

                val listener = argumentCaptor.value
                listener.onMetadataChanged(
                    bluetoothDevice1,
                    BluetoothDevice.METADATA_UNTETHERED_LEFT_BATTERY,
                    ByteArray(0)
                )
                assertThat(update).isEqualTo(Unit)
            }
        }
    }

    @Test
    fun metadataUpdate_triggerCallbackNonBatteryKey_doNothing() {
        with(kosmos) {
            testScope.runTest {
                val deviceItem = AvailableMediaDeviceItemFactory().create(context, cachedDevice1)
                val update by collectLastValue(interactor.metadataUpdate)
                deviceItemUpdate.emit(listOf(deviceItem))
                runCurrent()

                assertThat(update).isNull()
                verify(bluetoothAdapter)
                    .addOnMetadataChangedListener(
                        eq(bluetoothDevice1),
                        any(),
                        argumentCaptor.capture()
                    )

                val listener = argumentCaptor.value
                listener.onMetadataChanged(
                    bluetoothDevice1,
                    BluetoothDevice.METADATA_MODEL_NAME,
                    ByteArray(0)
                )

                assertThat(update).isNull()
            }
        }
    }

    companion object {
        private const val DEVICE_NAME = "DeviceName"
        private const val CONNECTION_SUMMARY = "ConnectionSummary"
        private const val DEVICE_ADDRESS = "04:52:C7:0B:D8:3C"
    }
}
