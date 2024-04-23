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

import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.settingslib.bluetooth.LocalBluetoothAdapter
import com.android.settingslib.bluetooth.LocalBluetoothManager
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class BluetoothStateInteractorTest : SysuiTestCase() {
    @get:Rule val mockitoRule: MockitoRule = MockitoJUnit.rule()
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var bluetoothStateInteractor: BluetoothStateInteractor

    @Mock private lateinit var bluetoothAdapter: LocalBluetoothAdapter
    @Mock private lateinit var localBluetoothManager: LocalBluetoothManager
    @Mock private lateinit var logger: BluetoothTileDialogLogger

    @Before
    fun setUp() {
        bluetoothStateInteractor =
            BluetoothStateInteractor(
                localBluetoothManager,
                logger,
                testScope.backgroundScope,
                testDispatcher
            )
        `when`(localBluetoothManager.bluetoothAdapter).thenReturn(bluetoothAdapter)
    }

    @Test
    fun testGet_isBluetoothEnabled() {
        testScope.runTest {
            `when`(bluetoothAdapter.isEnabled).thenReturn(true)

            assertThat(bluetoothStateInteractor.isBluetoothEnabled()).isTrue()
        }
    }

    @Test
    fun testGet_isBluetoothDisabled() {
        testScope.runTest {
            `when`(bluetoothAdapter.isEnabled).thenReturn(false)

            assertThat(bluetoothStateInteractor.isBluetoothEnabled()).isFalse()
        }
    }

    @Test
    fun testSet_bluetoothEnabled() {
        testScope.runTest {
            `when`(bluetoothAdapter.isEnabled).thenReturn(false)

            bluetoothStateInteractor.setBluetoothEnabled(true)
            verify(bluetoothAdapter).enable()
            verify(logger)
                .logBluetoothState(BluetoothStateStage.BLUETOOTH_STATE_VALUE_SET, true.toString())
        }
    }

    @Test
    fun testSet_bluetoothNoChange() {
        testScope.runTest {
            `when`(bluetoothAdapter.isEnabled).thenReturn(false)

            bluetoothStateInteractor.setBluetoothEnabled(false)
            verify(bluetoothAdapter, never()).enable()
        }
    }
}
