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

package com.android.systemui.keyguard.data.repository

import android.hardware.devicestate.DeviceStateManager
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.data.repository.RearDisplayStateRepository
import com.android.systemui.biometrics.data.repository.RearDisplayStateRepositoryImpl
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.withArgCaptor
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule

private const val NORMAL_DISPLAY_MODE_DEVICE_STATE = 2
private const val REAR_DISPLAY_MODE_DEVICE_STATE = 3

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(JUnit4::class)
class RearDisplayStateRepositoryTest : SysuiTestCase() {
    @JvmField @Rule var mockitoRule: MockitoRule = MockitoJUnit.rule()
    @Mock private lateinit var deviceStateManager: DeviceStateManager
    private lateinit var underTest: RearDisplayStateRepository

    private val testScope = TestScope(StandardTestDispatcher())
    private val fakeExecutor = FakeExecutor(FakeSystemClock())

    @Captor
    private lateinit var callbackCaptor: ArgumentCaptor<DeviceStateManager.DeviceStateCallback>

    @Before
    fun setUp() {
        val rearDisplayDeviceStates = intArrayOf(REAR_DISPLAY_MODE_DEVICE_STATE)
        mContext.orCreateTestableResources.addOverride(
            com.android.internal.R.array.config_rearDisplayDeviceStates,
            rearDisplayDeviceStates
        )

        underTest =
            RearDisplayStateRepositoryImpl(
                testScope.backgroundScope,
                mContext,
                deviceStateManager,
                fakeExecutor
            )
    }

    @Test
    fun updatesIsInRearDisplayMode_whenRearDisplayStateChanges() =
        testScope.runTest {
            val isInRearDisplayMode = collectLastValue(underTest.isInRearDisplayMode)
            runCurrent()

            val callback = deviceStateManager.captureCallback()

            callback.onStateChanged(NORMAL_DISPLAY_MODE_DEVICE_STATE)
            assertThat(isInRearDisplayMode()).isFalse()

            callback.onStateChanged(REAR_DISPLAY_MODE_DEVICE_STATE)
            assertThat(isInRearDisplayMode()).isTrue()
        }
}

private fun DeviceStateManager.captureCallback() =
    withArgCaptor<DeviceStateManager.DeviceStateCallback> {
        verify(this@captureCallback).registerCallback(any(), capture())
    }
