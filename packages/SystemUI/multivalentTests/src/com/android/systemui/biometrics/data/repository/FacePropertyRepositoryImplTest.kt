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

import android.graphics.Point
import android.hardware.biometrics.BiometricConstants.BIOMETRIC_LOCKOUT_NONE
import android.hardware.biometrics.BiometricConstants.BIOMETRIC_LOCKOUT_PERMANENT
import android.hardware.biometrics.BiometricConstants.BIOMETRIC_LOCKOUT_TIMED
import android.hardware.biometrics.SensorProperties
import android.hardware.camera2.CameraManager
import android.hardware.face.FaceManager
import android.hardware.face.FaceSensorPropertiesInternal
import android.hardware.face.IFaceAuthenticatorsRegisteredCallback
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.shared.model.LockoutMode
import com.android.systemui.biometrics.shared.model.SensorStrength
import com.android.systemui.common.ui.data.repository.FakeConfigurationRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.res.R
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.Executor
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
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class FacePropertyRepositoryImplTest : SysuiTestCase() {
    companion object {
        private const val LOGICAL_CAMERA_ID_OUTER_FRONT = "0"
        private const val LOGICAL_CAMERA_ID_INNER_FRONT = "1"
        private const val PHYSICAL_CAMERA_ID_OUTER_FRONT = "5"
        private const val PHYSICAL_CAMERA_ID_INNER_FRONT = "6"
        private val OUTER_FRONT_SENSOR_LOCATION = intArrayOf(100, 10, 20)
        private val INNER_FRONT_SENSOR_LOCATION = intArrayOf(200, 20, 30)
    }

    @JvmField @Rule val mockitoRule: MockitoRule = MockitoJUnit.rule()

    private lateinit var underTest: FacePropertyRepository
    private lateinit var dispatcher: TestDispatcher
    private lateinit var testScope: TestScope

    private val displayStateRepository = FakeDisplayStateRepository()
    private val configurationRepository = FakeConfigurationRepository()

    @Captor private lateinit var callback: ArgumentCaptor<IFaceAuthenticatorsRegisteredCallback>
    @Mock private lateinit var faceManager: FaceManager
    @Captor private lateinit var cameraCallback: ArgumentCaptor<CameraManager.AvailabilityCallback>
    @Mock private lateinit var cameraManager: CameraManager
    @Before
    fun setup() {
        overrideResource(R.string.config_protectedCameraId, LOGICAL_CAMERA_ID_OUTER_FRONT)
        overrideResource(R.string.config_protectedPhysicalCameraId, PHYSICAL_CAMERA_ID_OUTER_FRONT)
        overrideResource(R.string.config_protectedInnerCameraId, LOGICAL_CAMERA_ID_INNER_FRONT)
        overrideResource(
            R.string.config_protectedInnerPhysicalCameraId,
            PHYSICAL_CAMERA_ID_INNER_FRONT
        )
        overrideResource(R.array.config_face_auth_props, OUTER_FRONT_SENSOR_LOCATION)
        overrideResource(R.array.config_inner_face_auth_props, INNER_FRONT_SENSOR_LOCATION)

        dispatcher = StandardTestDispatcher()
        testScope = TestScope(dispatcher)
        underTest = createRepository(faceManager)
    }

    private fun createRepository(manager: FaceManager? = faceManager) =
        FacePropertyRepositoryImpl(
            context,
            context.mainExecutor,
            testScope.backgroundScope,
            dispatcher,
            manager,
            cameraManager,
            displayStateRepository,
            configurationRepository,
        )

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

    @Test
    fun providesTheSensorLocationOfOuterCameraFromOnPhysicalCameraAvailable() {
        testScope.runTest {
            runCurrent()
            collectLastValue(underTest.sensorLocation)

            verify(faceManager).addAuthenticatorsRegisteredCallback(callback.capture())
            callback.value.onAllAuthenticatorsRegistered(
                listOf(createSensorProperties(1, SensorProperties.STRENGTH_STRONG))
            )
            runCurrent()
            verify(cameraManager)
                .registerAvailabilityCallback(any(Executor::class.java), cameraCallback.capture())

            cameraCallback.value.onPhysicalCameraAvailable("1", PHYSICAL_CAMERA_ID_OUTER_FRONT)
            runCurrent()

            val sensorLocation by collectLastValue(underTest.sensorLocation)
            assertThat(sensorLocation)
                .isEqualTo(Point(OUTER_FRONT_SENSOR_LOCATION[0], OUTER_FRONT_SENSOR_LOCATION[1]))
        }
    }

    @Test
    fun providesTheSensorLocationOfInnerCameraFromOnPhysicalCameraAvailable() {
        testScope.runTest {
            runCurrent()
            collectLastValue(underTest.sensorLocation)

            verify(faceManager).addAuthenticatorsRegisteredCallback(callback.capture())
            callback.value.onAllAuthenticatorsRegistered(
                listOf(createSensorProperties(1, SensorProperties.STRENGTH_STRONG))
            )
            runCurrent()
            verify(cameraManager)
                .registerAvailabilityCallback(any(Executor::class.java), cameraCallback.capture())

            cameraCallback.value.onPhysicalCameraAvailable("1", PHYSICAL_CAMERA_ID_INNER_FRONT)
            runCurrent()

            val sensorLocation by collectLastValue(underTest.sensorLocation)
            assertThat(sensorLocation)
                .isEqualTo(Point(INNER_FRONT_SENSOR_LOCATION[0], INNER_FRONT_SENSOR_LOCATION[1]))
        }
    }

    @Test
    fun providesTheSensorLocationOfCameraFromOnPhysicalCameraUnavailable() {
        testScope.runTest {
            runCurrent()
            collectLastValue(underTest.sensorLocation)

            verify(faceManager).addAuthenticatorsRegisteredCallback(callback.capture())
            callback.value.onAllAuthenticatorsRegistered(
                listOf(createSensorProperties(1, SensorProperties.STRENGTH_STRONG))
            )
            runCurrent()
            verify(cameraManager)
                .registerAvailabilityCallback(any(Executor::class.java), cameraCallback.capture())

            cameraCallback.value.onPhysicalCameraUnavailable("1", PHYSICAL_CAMERA_ID_INNER_FRONT)
            runCurrent()

            val sensorLocation by collectLastValue(underTest.sensorLocation)
            assertThat(sensorLocation)
                .isEqualTo(Point(OUTER_FRONT_SENSOR_LOCATION[0], OUTER_FRONT_SENSOR_LOCATION[1]))
        }
    }

    @Test
    fun providesTheCameraInfoOnCameraAvailableChange() {
        testScope.runTest {
            runCurrent()
            collectLastValue(underTest.cameraInfo)

            verify(faceManager).addAuthenticatorsRegisteredCallback(callback.capture())
            callback.value.onAllAuthenticatorsRegistered(
                listOf(createSensorProperties(1, SensorProperties.STRENGTH_STRONG))
            )
            runCurrent()
            verify(cameraManager)
                .registerAvailabilityCallback(any(Executor::class.java), cameraCallback.capture())

            cameraCallback.value.onPhysicalCameraAvailable("0", PHYSICAL_CAMERA_ID_OUTER_FRONT)
            runCurrent()

            val cameraInfo by collectLastValue(underTest.cameraInfo)
            assertThat(cameraInfo)
                .isEqualTo(
                    CameraInfo(
                        "0",
                        PHYSICAL_CAMERA_ID_OUTER_FRONT,
                        Point(OUTER_FRONT_SENSOR_LOCATION[0], OUTER_FRONT_SENSOR_LOCATION[1])
                    )
                )
        }
    }

    private fun createSensorProperties(id: Int, strength: Int) =
        FaceSensorPropertiesInternal(id, strength, 0, emptyList(), 1, false, false, false)
}
