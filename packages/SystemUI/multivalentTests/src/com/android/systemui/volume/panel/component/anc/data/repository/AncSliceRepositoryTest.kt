/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.android.systemui.volume.panel.component.anc.data.repository

import android.bluetooth.BluetoothDevice
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.settingslib.bluetooth.CachedBluetoothDevice
import com.android.settingslib.media.BluetoothMediaDevice
import com.android.settingslib.media.MediaDevice
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.volume.localMediaRepository
import com.android.systemui.volume.localMediaRepositoryFactory
import com.android.systemui.volume.panel.component.anc.FakeSliceFactory
import com.android.systemui.volume.panel.component.anc.sliceViewManager
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class AncSliceRepositoryTest : SysuiTestCase() {

    private val kosmos = testKosmos()

    private lateinit var underTest: AncSliceRepository

    @Before
    fun setup() {
        with(kosmos) {
            val slice = FakeSliceFactory.createSlice(hasError = false, hasSliceItem = true)
            whenever(sliceViewManager.bindSlice(any<Uri>())).thenReturn(slice)

            underTest =
                AncSliceRepositoryImpl(
                    localMediaRepositoryFactory,
                    testScope.testScheduler,
                    sliceViewManager,
                )
        }
    }

    @Test
    fun noConnectedDevice_noSlice() {
        with(kosmos) {
            testScope.runTest {
                localMediaRepository.updateCurrentConnectedDevice(null)

                val slice by collectLastValue(underTest.ancSlice(1))
                runCurrent()

                assertThat(slice).isNull()
            }
        }
    }

    @Test
    fun connectedDevice_sliceReturned() {
        with(kosmos) {
            testScope.runTest {
                localMediaRepository.updateCurrentConnectedDevice(createMediaDevice())

                val slice by collectLastValue(underTest.ancSlice(1))
                runCurrent()

                assertThat(slice).isNotNull()
            }
        }
    }

    private fun createMediaDevice(sliceUri: String = "content://test.slice"): MediaDevice {
        val bluetoothDevice: BluetoothDevice = mock {
            whenever(getMetadata(any()))
                .thenReturn(
                    ("<HEARABLE_CONTROL_SLICE_WITH_WIDTH>" +
                            sliceUri +
                            "</HEARABLE_CONTROL_SLICE_WITH_WIDTH>")
                        .toByteArray()
                )
        }
        val cachedBluetoothDevice: CachedBluetoothDevice = mock {
            whenever(device).thenReturn(bluetoothDevice)
        }
        return mock<BluetoothMediaDevice> {
            whenever(cachedDevice).thenReturn(cachedBluetoothDevice)
        }
    }
}
