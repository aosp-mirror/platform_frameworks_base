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
import com.android.settingslib.bluetooth.LocalBluetoothManager
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import kotlin.test.Test
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.Mock
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule

@SmallTest
@RunWith(AndroidJUnit4::class)
class BluetoothAutoOnInteractorTest : SysuiTestCase() {
    @get:Rule val mockitoRule: MockitoRule = MockitoJUnit.rule()
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val bluetoothAdapter =
        mock<BluetoothAdapter> {
            var autoOn = false
            whenever(isAutoOnEnabled).thenAnswer { autoOn }

            whenever(setAutoOnEnabled(anyBoolean())).thenAnswer { invocation ->
                autoOn = invocation.getArgument(0) as Boolean
                autoOn
            }
        }
    @Mock private lateinit var localBluetoothManager: LocalBluetoothManager
    private lateinit var bluetoothAutoOnInteractor: BluetoothAutoOnInteractor

    @Before
    fun setUp() {
        bluetoothAutoOnInteractor =
            BluetoothAutoOnInteractor(
                BluetoothAutoOnRepository(
                    localBluetoothManager,
                    bluetoothAdapter,
                    testScope.backgroundScope,
                    testDispatcher,
                )
            )
    }

    @Test
    fun testSetEnabled_bluetoothAutoOnUnsupported_doNothing() {
        testScope.runTest {
            whenever(bluetoothAdapter.isAutoOnSupported).thenReturn(false)

            bluetoothAutoOnInteractor.setEnabled(true)
            runCurrent()

            assertFalse(bluetoothAdapter.isAutoOnEnabled)
        }
    }

    @Test
    fun testSetEnabled_bluetoothAutoOnSupported_setNewValue() {
        testScope.runTest {
            whenever(bluetoothAdapter.isAutoOnSupported).thenReturn(true)

            bluetoothAutoOnInteractor.setEnabled(true)
            runCurrent()

            assertTrue(bluetoothAdapter.isAutoOnEnabled)
        }
    }
}
