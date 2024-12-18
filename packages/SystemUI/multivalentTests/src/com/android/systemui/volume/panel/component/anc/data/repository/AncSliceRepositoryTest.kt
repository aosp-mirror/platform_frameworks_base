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
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.android.systemui.volume.panel.component.anc.FakeSliceFactory
import com.android.systemui.volume.panel.component.anc.sliceViewManager
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class AncSliceRepositoryTest : SysuiTestCase() {

    private val kosmos = testKosmos()

    private lateinit var underTest: AncSliceRepository

    @Before
    fun setup() {
        with(kosmos) {
            val slice = FakeSliceFactory.createSlice(hasError = false, hasSliceItem = true)
            whenever(sliceViewManager.bindSlice(any<Uri>())).thenReturn(slice)

            underTest = AncSliceRepositoryImpl(testScope.testScheduler, sliceViewManager)
        }
    }

    @Test
    fun connectedDevice_noUri_noSlice() {
        with(kosmos) {
            testScope.runTest {
                val slice by
                    collectLastValue(
                        underTest.ancSlice(
                            device = createMediaDevice(""),
                            width = 1,
                            isCollapsed = false,
                            hideLabel = false,
                        )
                    )
                runCurrent()

                assertThat(slice).isNull()
            }
        }
    }

    @Test
    fun connectedDevice_hasUri_sliceReturned() {
        with(kosmos) {
            testScope.runTest {
                val slice by
                    collectLastValue(
                        underTest.ancSlice(
                            device = createMediaDevice("content://test.slice"),
                            width = 1,
                            isCollapsed = false,
                            hideLabel = false,
                        )
                    )
                runCurrent()

                assertThat(slice).isNotNull()
            }
        }
    }

    private fun createMediaDevice(sliceUri: String): BluetoothDevice = mock {
        on { getMetadata(any()) }
            .thenReturn(
                ("<HEARABLE_CONTROL_SLICE_WITH_WIDTH>" +
                        sliceUri +
                        "</HEARABLE_CONTROL_SLICE_WITH_WIDTH>")
                    .toByteArray()
            )
    }
}
