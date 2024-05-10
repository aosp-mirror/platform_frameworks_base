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
 *
 */

package com.android.systemui.biometrics.data.repository

import android.hardware.biometrics.BiometricConstants.BIOMETRIC_LOCKOUT_NONE
import android.hardware.biometrics.BiometricConstants.BIOMETRIC_LOCKOUT_PERMANENT
import android.hardware.biometrics.BiometricConstants.BIOMETRIC_LOCKOUT_TIMED
import android.hardware.biometrics.SensorProperties
import android.hardware.face.FaceManager
import android.hardware.face.FaceSensorPropertiesInternal
import android.hardware.face.IFaceAuthenticatorsRegisteredCallback
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.shared.model.LockoutMode
import com.android.systemui.biometrics.shared.model.SensorStrength
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
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

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(JUnit4::class)
class FacePropertyRepositoryImplTest : SysuiTestCase() {
    @JvmField @Rule val mockitoRule: MockitoRule = MockitoJUnit.rule()

    private lateinit var underTest: FacePropertyRepository
    private lateinit var dispatcher: TestDispatcher
    private lateinit var testScope: TestScope

    @Captor private lateinit var callback: ArgumentCaptor<IFaceAuthenticatorsRegisteredCallback>
    @Mock private lateinit var faceManager: FaceManager
    @Before
    fun setup() {
        dispatcher = StandardTestDispatcher()
        testScope = TestScope(dispatcher)
        underTest = createRepository(faceManager)
    }

    private fun createRepository(manager: FaceManager? = faceManager) =
        FacePropertyRepositoryImpl(testScope.backgroundScope, dispatcher, manager)

    @Test
    fun whenFaceManagerIsNotPresentIsNull() =
        testScope.runTest {
            underTest = createRepository(null)
            val sensor = collectLastValue(underTest.sensorInfo)

            assertThat(sensor()).isNull()
        }

    @Test
    fun providesTheValuePassedToTheAuthenticatorsRegisteredCallback() {
        testScope.runTest {
            runCurrent()
            verify(faceManager).addAuthenticatorsRegisteredCallback(callback.capture())

            callback.value.onAllAuthenticatorsRegistered(
                listOf(createSensorProperties(1, SensorProperties.STRENGTH_STRONG))
            )
            runCurrent()

            val sensor by collectLastValue(underTest.sensorInfo)
            assertThat(sensor).isEqualTo(FaceSensorInfo(1, SensorStrength.STRONG))
        }
    }

    @Test
    fun providesTheNoneLockoutModeWhenFaceManagerIsNotAvailable() =
        testScope.runTest {
            underTest = createRepository(null)

            assertThat(underTest.getLockoutMode(-1)).isEqualTo(LockoutMode.NONE)
        }

    @Test
    fun providesTheLockoutModeFromFaceManager() =
        testScope.runTest {
            val sensorId = 99
            val userId = 999
            runCurrent()
            verify(faceManager).addAuthenticatorsRegisteredCallback(callback.capture())
            callback.value.onAllAuthenticatorsRegistered(
                listOf(createSensorProperties(sensorId, SensorProperties.STRENGTH_STRONG))
            )
            runCurrent()

            whenever(faceManager.getLockoutModeForUser(sensorId, userId))
                .thenReturn(BIOMETRIC_LOCKOUT_TIMED)
            assertThat(underTest.getLockoutMode(userId)).isEqualTo(LockoutMode.TIMED)

            whenever(faceManager.getLockoutModeForUser(sensorId, userId))
                .thenReturn(BIOMETRIC_LOCKOUT_PERMANENT)
            assertThat(underTest.getLockoutMode(userId)).isEqualTo(LockoutMode.PERMANENT)

            whenever(faceManager.getLockoutModeForUser(sensorId, userId))
                .thenReturn(BIOMETRIC_LOCKOUT_NONE)
            assertThat(underTest.getLockoutMode(userId)).isEqualTo(LockoutMode.NONE)
        }

    private fun createSensorProperties(id: Int, strength: Int) =
        FaceSensorPropertiesInternal(id, strength, 0, emptyList(), 1, false, false, false)
}
