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

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.shared.model.DevicePosture
import com.android.systemui.statusbar.policy.DevicePostureController
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(JUnit4::class)
class DevicePostureRepositoryTest : SysuiTestCase() {
    private lateinit var underTest: DevicePostureRepository
    private lateinit var testScope: TestScope
    @Mock private lateinit var devicePostureController: DevicePostureController
    @Captor private lateinit var callback: ArgumentCaptor<DevicePostureController.Callback>

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        testScope = TestScope()
        underTest = DevicePostureRepositoryImpl(postureController = devicePostureController)
    }

    @Test
    fun postureChangesArePropagated() =
        testScope.runTest {
            whenever(devicePostureController.devicePosture)
                .thenReturn(DevicePostureController.DEVICE_POSTURE_FLIPPED)
            val currentPosture = collectLastValue(underTest.currentDevicePosture)
            assertThat(currentPosture()).isEqualTo(DevicePosture.FLIPPED)

            verify(devicePostureController).addCallback(callback.capture())

            callback.value.onPostureChanged(DevicePostureController.DEVICE_POSTURE_UNKNOWN)
            assertThat(currentPosture()).isEqualTo(DevicePosture.UNKNOWN)

            callback.value.onPostureChanged(DevicePostureController.DEVICE_POSTURE_CLOSED)
            assertThat(currentPosture()).isEqualTo(DevicePosture.CLOSED)

            callback.value.onPostureChanged(DevicePostureController.DEVICE_POSTURE_HALF_OPENED)
            assertThat(currentPosture()).isEqualTo(DevicePosture.HALF_OPENED)

            callback.value.onPostureChanged(DevicePostureController.DEVICE_POSTURE_OPENED)
            assertThat(currentPosture()).isEqualTo(DevicePosture.OPENED)

            callback.value.onPostureChanged(DevicePostureController.DEVICE_POSTURE_FLIPPED)
            assertThat(currentPosture()).isEqualTo(DevicePosture.FLIPPED)
        }
}
