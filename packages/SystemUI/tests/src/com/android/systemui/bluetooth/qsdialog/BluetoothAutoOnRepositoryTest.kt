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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.settingslib.bluetooth.BluetoothEventManager
import com.android.settingslib.bluetooth.LocalBluetoothManager
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule

@SmallTest
@RunWith(AndroidJUnit4::class)
class BluetoothAutoOnRepositoryTest : SysuiTestCase() {
    @get:Rule val mockitoRule: MockitoRule = MockitoJUnit.rule()
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    @Mock private lateinit var bluetoothAdapter: BluetoothAdapter
    @Mock private lateinit var localBluetoothManager: LocalBluetoothManager
    @Mock private lateinit var eventManager: BluetoothEventManager

    private lateinit var bluetoothAutoOnRepository: BluetoothAutoOnRepository

    @Before
    fun setUp() {
        whenever(localBluetoothManager.eventManager).thenReturn(eventManager)
        bluetoothAutoOnRepository =
            BluetoothAutoOnRepository(
                localBluetoothManager,
                bluetoothAdapter,
                testScope.backgroundScope,
                testDispatcher,
            )
    }

    @Test
    fun testIsAutoOn_returnFalse() {
        testScope.runTest {
            whenever(bluetoothAdapter.isAutoOnEnabled).thenReturn(false)
            val actualValue by collectLastValue(bluetoothAutoOnRepository.isAutoOn)

            runCurrent()

            assertThat(actualValue).isEqualTo(false)
        }
    }

    @Test
    fun testIsAutoOn_returnTrue() {
        testScope.runTest {
            whenever(bluetoothAdapter.isAutoOnEnabled).thenReturn(true)
            val actualValue by collectLastValue(bluetoothAutoOnRepository.isAutoOn)

            runCurrent()

            assertThat(actualValue).isEqualTo(true)
        }
    }

    @Test
    fun testIsAutoOnSupported_returnTrue() {
        testScope.runTest {
            whenever(bluetoothAdapter.isAutoOnSupported).thenReturn(true)
            val actualValue = bluetoothAutoOnRepository.isAutoOnSupported()

            runCurrent()

            assertThat(actualValue).isEqualTo(true)
        }
    }
}
