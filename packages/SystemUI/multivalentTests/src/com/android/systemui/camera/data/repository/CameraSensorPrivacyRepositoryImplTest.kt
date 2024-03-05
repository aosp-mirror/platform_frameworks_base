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

package com.android.systemui.camera.data.repository

import android.hardware.SensorPrivacyManager
import android.os.UserHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testScope
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers
import org.mockito.Mockito

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
@android.platform.test.annotations.EnabledOnRavenwood
class CameraSensorPrivacyRepositoryImplTest : SysuiTestCase() {
    private val kosmos = Kosmos()
    private val testScope = kosmos.testScope
    private val testUser = UserHandle.of(1)
    private val privacyManager = mock<SensorPrivacyManager>()
    private val underTest =
        CameraSensorPrivacyRepositoryImpl(
            testScope.testScheduler,
            testScope.backgroundScope,
            privacyManager
        )

    @Test
    fun isEnabled_2TimesForSameUserReturnsCachedFlow() =
        testScope.runTest {
            val flow1 = underTest.isEnabled(testUser)
            val flow2 = underTest.isEnabled(testUser)
            runCurrent()

            assertThat(flow1).isEqualTo(flow2)
        }

    @Test
    fun isEnabled_2TimesForDifferentUsersReturnsTwoDifferentFlows() =
        testScope.runTest {
            val user2 = UserHandle.of(2)

            val flow1 = underTest.isEnabled(testUser)
            val flow2 = underTest.isEnabled(user2)
            runCurrent()

            assertThat(flow1).isNotEqualTo(flow2)
        }

    @Test
    fun isEnabled_dataMatchesSensorPrivacyManager() =
        testScope.runTest {
            val isEnabled = collectLastValue(underTest.isEnabled(testUser))

            val captor =
                ArgumentCaptor.forClass(
                    SensorPrivacyManager.OnSensorPrivacyChangedListener::class.java
                )
            runCurrent()
            assertThat(isEnabled()).isEqualTo(false)

            Mockito.verify(privacyManager)
                .addSensorPrivacyListener(
                    ArgumentMatchers.eq(SensorPrivacyManager.Sensors.CAMERA),
                    ArgumentMatchers.eq(testUser.identifier),
                    captor.capture()
                )
            val sensorPrivacyCallback = captor.value!!

            sensorPrivacyCallback.onSensorPrivacyChanged(SensorPrivacyManager.Sensors.CAMERA, true)
            runCurrent()
            assertThat(isEnabled()).isEqualTo(true)

            sensorPrivacyCallback.onSensorPrivacyChanged(SensorPrivacyManager.Sensors.CAMERA, false)
            runCurrent()
            assertThat(isEnabled()).isEqualTo(false)
        }
}
